package com.mcpdev.tooling.executor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-call abort state. The executor's timer/canceller sets the reason before
 * interrupting the worker thread; the funnel reads it afterwards to classify the
 * outcome.
 *
 * <p>{@link #complete()} closes the race where a deadline fires just as the tool
 * returns: a completed call can no longer be aborted, so a slow-but-successful call
 * is never mislabelled as a timeout.
 */
public final class CallState {

    public enum Reason {
        TIMEOUT,
        CANCELLED
    }

    private final AtomicReference<Reason> reason = new AtomicReference<>();
    private volatile boolean finished;

    /** @return true if this call set the reason (caller wins the race exactly once) */
    public boolean abort(Reason newReason) {
        if (finished) {
            return false;
        }
        boolean won = reason.compareAndSet(null, newReason);
        return won;
    }

    public void complete() {
        finished = true;
    }

    public boolean aborted() {
        return reason.get() != null;
    }

    public Reason reason() {
        return reason.get();
    }
}
