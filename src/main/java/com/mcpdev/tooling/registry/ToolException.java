package com.mcpdev.tooling.registry;

/**
 * Base of the tool-domain exception hierarchy. Messages of subclasses are client-safe;
 * an adapter serializes them into an isError=true {@code CallToolResult} — never as
 * JSON-RPC-level errors and never with stack traces.
 */
public abstract class ToolException extends RuntimeException {

    protected ToolException(String message) {
        super(message);
    }

    protected ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
