package com.mcpdev.tooling.executor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 mechanics: concurrency on virtual threads, deadline, cancellation,
 * backpressure, and no permit leaks.
 */
class ToolExecutorTest {

    @Test
    @DisplayName("64 calls really run concurrently on virtual threads")
    void concurrency() throws Exception {
        try (ToolExecutor executor = new ToolExecutor(64, 500, 10_000, 0)) {
            int n = 64;
            AtomicInteger current = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            AtomicBoolean allVirtual = new AtomicBoolean(true);
            CountDownLatch allInside = new CountDownLatch(n);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);

            for (int i = 0; i < n; i++) {
                String callId = "c" + i;
                executor.scheduler().schedule(() -> {
                    try {
                        executor.execute(executor.register(callId), () -> {
                            if (!Thread.currentThread().isVirtual()) {
                                allVirtual.set(false);
                            }
                            int now = current.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            allInside.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            current.decrementAndGet();
                            return null;
                        });
                    } catch (Exception ignored) {
                        // surfaced through latches/assertions below
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(allInside.await(5, TimeUnit.SECONDS))
                    .as("all 64 calls should be inside the tool at the same time")
                    .isTrue();
            assertThat(peak.get()).isEqualTo(n);
            release.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(allVirtual).as("every tool call runs on a virtual thread").isTrue();
            assertThat(executor.stats().permitsInUse()).isZero();
            assertThat(executor.stats().active()).isZero();
        }
    }

    @Test
    @DisplayName("deadline interrupts the worker and is classified TIMEOUT")
    void timeout() {
        try (ToolExecutor executor = new ToolExecutor(8, 500, 150, 0)) {
            ToolExecutor.Handle handle = executor.register("t1");
            AtomicBoolean interrupted = new AtomicBoolean();

            long startNanos = System.nanoTime();
            executor.execute(handle, () -> {
                while (!Thread.currentThread().isInterrupted()) {
                    LockSupport.parkNanos(5_000_000);
                }
                interrupted.set(true);
                return "late";
            });
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(interrupted).as("worker observed the interrupt").isTrue();
            assertThat(handle.state().aborted()).isTrue();
            assertThat(handle.state().reason()).isEqualTo(CallState.Reason.TIMEOUT);
            assertThat(elapsedMs).isLessThan(2_000);
            assertThat(executor.stats().timedOut()).isEqualTo(1);
            assertThat(executor.stats().permitsInUse()).isZero();
        }
    }

    @Test
    @DisplayName("cancel interrupts a running call and is classified CANCELLED")
    void cancellation() throws Exception {
        try (ToolExecutor executor = new ToolExecutor(8, 500, 10_000, 0)) {
            ToolExecutor.Handle handle = executor.register("42");
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch running = new CountDownLatch(1);

            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    executor.execute(handle, () -> {
                        running.countDown();
                        while (!Thread.currentThread().isInterrupted()) {
                            LockSupport.parkNanos(5_000_000);
                        }
                        return "stopped";
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });

            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.cancel("42")).isTrue();
            caller.join(5_000);

            assertThat(thrown.get()).isNull(); // supplier returned normally after the park returned
            assertThat(handle.state().reason()).isEqualTo(CallState.Reason.CANCELLED);
            assertThat(executor.stats().cancelled()).isEqualTo(1);
            assertThat(executor.stats().permitsInUse()).isZero();
            assertThat(executor.cancel("42")).as("already finished").isFalse();
        }
    }

    @Test
    @DisplayName("backpressure: queue wait expiry sheds load instead of queueing forever")
    void backpressure() throws Exception {
        try (ToolExecutor executor = new ToolExecutor(1, 100, 10_000, 0)) {
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ToolExecutor.Handle first = executor.register("first");

            executor.scheduler().schedule(() -> executor.execute(first, () -> {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> executor.execute(executor.register("second"), () -> "x"))
                    .isInstanceOf(ServerBusyException.class)
                    .hasMessageContaining("server busy");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(elapsedMs).isGreaterThanOrEqualTo(90).isLessThan(2_000);
            assertThat(executor.stats().rejectedBusy()).isEqualTo(1);

            release.countDown();
            executor.scheduler().schedule(() -> executor.execute(
                    executor.register("third"), () -> "ok"));
            Thread.sleep(200);
            assertThat(executor.stats().permitsInUse()).isZero();
        }
    }

    @Test
    @DisplayName("cancel before the worker starts: task never runs, permit never leaks")
    void cancelBeforeStart() {
        try (ToolExecutor executor = new ToolExecutor(4, 500, 10_000, 0)) {
            ToolExecutor.Handle handle = executor.register("early");
            assertThat(executor.cancel("early")).isTrue();

            AtomicBoolean ran = new AtomicBoolean();
            assertThatThrownBy(() -> executor.execute(handle, () -> {
                ran.set(true);
                return null;
            })).isInstanceOf(CallAbortedException.class);

            assertThat(ran).isFalse();
            assertThat(executor.stats().permitsInUse()).isZero();
            assertThat(executor.stats().cancelled()).isEqualTo(1);
        }
    }
}
