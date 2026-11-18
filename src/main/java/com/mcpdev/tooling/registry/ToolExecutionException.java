package com.mcpdev.tooling.registry;

/**
 * Expected domain failure during execution: resource not found, path outside sandbox,
 * query rejected, size cap exceeded, timeout. Message must be client-safe by contract.
 */
public final class ToolExecutionException extends ToolException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
