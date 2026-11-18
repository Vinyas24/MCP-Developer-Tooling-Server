package com.mcpdev.tooling.registry;

/**
 * Uniform tool outcome: text for the LLM, optional structured payload
 * (serialized into CallToolResult.structuredContent) for programmatic clients.
 */
public record ToolResult(String text, boolean isError, Object structured) {

    public ToolResult(String text, boolean isError) {
        this(text, isError, null);
    }

    public static ToolResult text(String text) {
        return new ToolResult(text, false, null);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true, null);
    }

    public static ToolResult structured(String text, Object structured) {
        return new ToolResult(text, false, structured);
    }
}
