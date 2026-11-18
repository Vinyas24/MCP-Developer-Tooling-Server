package com.mcpdev.tooling.registry;

/**
 * The client sent arguments the tool cannot accept: missing required property, wrong
 * type, unknown property, or a semantic bound violation (e.g. repeat=60 with max 50).
 * Maps to an isError=true result; the client should retry with corrected arguments.
 */
public final class ToolInputException extends ToolException {

    public ToolInputException(String message) {
        super(message);
    }

    public ToolInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
