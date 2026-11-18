package com.mcpdev.tooling.executor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Bounded, cancellable call executor for tool invocations.
 *
 * <p><b>Concurrency</b>: one VIRTUAL thread per call, so 1,000 concurrent calls cost
 * 1,000 cheap heap-resident threads instead of 1,000 platform threads. Blocking tool
 * code (JDBC, file IO) parks a virtual thread, not a carrier.
 *
 * <p><b>Backpressure</b>: a {@link Semaphore} bounds in-flight calls AND queued
 * waiters. Waiting is bounded by {@code queueWaitMillis}; past that the call is
 * rejected with {@link ServerBusyException} (audited DENIED_BACKPRESSURE) instead of
 * accumulating unbounded work — load shedding is a feature.
 *
 * <p><b>Deadline</b>: every call gets ONE end-to-end deadline (queue wait +
 * execution). On expiry the worker virtual thread is interrupted, unblocking the
 * usual interruptible waits (Hikari semaphore, socket IO, sleep).
 *
 * <p><b>Cancellation</b>: MCP {@code notifications/cancelled} carries the JSON-RPC
 * request id; the transport calls {@link #cancel(String)}, which interrupts the same
 * worker. Interrupts are cooperative — a tool that swallows them runs until its own
 * inner timeout, so the permit is released only when the task actually exits.
 */
public final class ToolExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /**
     * Registered BEFORE the worker thread exists, so a cancel that lands while the
     * call is still queued (or before its virtual thread is scheduled) is not lost:
     * the state is set, and the worker sees {@code aborted()} on attach.
     */
    public static final class Handle {
        private final String callId;
        private final CallState state = new CallState();
        private volatile Thread thread;
        private volatile Runnable abortResponder;

        Handle(String callId) {
            this.callId = callId;
        }

        public String callId() {
            return callId;
        }

        public CallState state() {
            return state;
        }

        /**
         * Called once when the call is aborted (timeout or cancel), on the aborting
         * thread. Lets the async handler answer the client immediately even if the
         * tool ignores interrupts (JDBC), while the worker unwinds in the background.
         */
        public void onAbort(Runnable responder) {
            this.abortResponder = responder;
        }
    }

    private final int maxConcurrent;
    private final long queueWaitMillis;
    private final long callTimeoutMillis;
    private final Semaphore permits;
    private final ExecutorService workers;
    private final Scheduler reactorScheduler;
    private final ScheduledExecutorService timer;
    private final Map<String, Handle> running = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final LongAdder started = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder timedOut = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder rejected = new LongAdder();

    public ToolExecutor(int maxConcurrent, long queueWaitMillis, long callTimeoutMillis,
                        long statsIntervalSeconds) {
        this.maxConcurrent = maxConcurrent;
        this.queueWaitMillis = queueWaitMillis;
        this.callTimeoutMillis = callTimeoutMillis;
        this.permits = new Semaphore(maxConcurrent, true);
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.reactorScheduler = Schedulers.fromExecutorService(workers);
        this.timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("tool-executor-timer").daemon().factory());
        if (statsIntervalSeconds > 0) {
            timer.scheduleAtFixedRate(this::logStats, statsIntervalSeconds,
                    statsIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    /** Reactor scheduler backed by the virtual-thread pool (never blocks a carrier). */
    public Scheduler scheduler() {
        return reactorScheduler;
    }

    public long callTimeoutMillis() {
        return callTimeoutMillis;
    }

    /** Register a call before scheduling it, so early cancels are honoured. */
    public Handle register(String callId) {
        Handle handle = new Handle(callId);
        Handle previous = running.put(callId, handle);
        if (previous != null) {
            log.warn("duplicate in-flight call id '{}' — replacing registry entry", callId);
        }
        return handle;
    }

    /**
     * Run {@code task} under the call's deadline and a concurrency permit. The caller
     * audits the resulting {@link CallState} — this method only enforces mechanics.
     */
    public <T> T execute(Handle handle, Supplier<T> task) {
        handle.thread = Thread.currentThread();
        ScheduledFuture<?> deadline = timer.schedule(() -> abort(handle, CallState.Reason.TIMEOUT),
                callTimeoutMillis, TimeUnit.MILLISECONDS);

        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(queueWaitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                rejected.increment();
                throw new ServerBusyException("server busy: " + maxConcurrent
                        + " calls in flight, queue wait exceeded " + queueWaitMillis + "ms");
            }
            if (handle.state.aborted()) {
                throw new CallAbortedException(handle.state.reason());
            }
            started.increment();
            active.incrementAndGet();
            T result = task.get();
            completed.increment();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CallState.Reason reason = handle.state.aborted()
                    ? handle.state.reason() : CallState.Reason.CANCELLED;
            throw new CallAbortedException(reason);
        } finally {
            deadline.cancel(false);
            running.remove(handle.callId(), handle);
            handle.thread = null;
            if (acquired) {
                permits.release();
                active.decrementAndGet();
            }
            Thread.interrupted(); // never leak an interrupt to the next task
        }
    }

    /** Cancel a queued or running call by JSON-RPC request id. */
    public boolean cancel(String callId) {
        Handle handle = running.get(callId);
        if (handle == null) {
            return false;
        }
        return abort(handle, CallState.Reason.CANCELLED);
    }

    private boolean abort(Handle handle, CallState.Reason reason) {
        if (!handle.state.abort(reason)) {
            return false;
        }
        if (reason == CallState.Reason.TIMEOUT) {
            timedOut.increment();
        } else {
            cancelled.increment();
        }
        Thread worker = handle.thread;
        if (worker != null) {
            worker.interrupt();
        }
        Runnable responder = handle.abortResponder;
        if (responder != null) {
            try {
                responder.run();
            } catch (RuntimeException e) {
                log.warn("abort responder failed for call '{}'", handle.callId(), e);
            }
        }
        return true;
    }

    public ExecutorStats stats() {
        return new ExecutorStats(
                active.get(),
                maxConcurrent - permits.availablePermits(),
                maxConcurrent,
                started.sum(),
                completed.sum(),
                timedOut.sum(),
                cancelled.sum(),
                rejected.sum());
    }

    private void logStats() {
        ExecutorStats s = stats();
        log.info("executor stats active={} permits={}/{} started={} completed={} "
                        + "timedOut={} cancelled={} rejected={}",
                s.active(), s.permitsInUse(), s.maxConcurrent(), s.started(),
                s.completed(), s.timedOut(), s.cancelled(), s.rejectedBusy());
    }

    @Override
    public void close() {
        logStats();
        workers.shutdown();
        timer.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
