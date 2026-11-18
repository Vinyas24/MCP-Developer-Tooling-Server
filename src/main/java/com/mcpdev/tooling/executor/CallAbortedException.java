package com.mcpdev.tooling.executor;

/**
 * Raised when a call is aborted by the executor (deadline or client cancel). The
 * funnel maps {@link CallState.Reason} to the audit outcome.
 */
public class CallAbortedException extends RuntimeException {

    private final CallState.Reason reason;

    public CallAbortedException(CallState.Reason reason) {
        super(reason == CallState.Reason.TIMEOUT ? "timeout" : "cancelled");
        this.reason = reason;
    }

    public CallState.Reason reason() {
        return reason;
    }
}
