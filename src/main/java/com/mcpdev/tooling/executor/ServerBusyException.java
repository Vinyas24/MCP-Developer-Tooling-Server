package com.mcpdev.tooling.executor;

/**
 * Thrown when the bounded call queue is full. Distinct type so the funnel can audit
 * DENIED_BACKPRESSURE instead of a generic tool error.
 */
public class ServerBusyException extends RuntimeException {

    public ServerBusyException(String message) {
        super(message);
    }
}
