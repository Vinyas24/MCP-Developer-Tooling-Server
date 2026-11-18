package com.mcpdev.tooling.registry;

/**
 * A tool exposed via MCP. Adding a tool = implementing this interface as a
 * {@code @Component}; the {@link ToolRegistrar} discovers and registers it at startup.
 *
 * <p>Contract: tools never touch the transport; they receive strictly typed,
 * already-validated arguments and return a {@link ToolResult}. Throwing
 * {@link ToolInputException} / {@link ToolExecutionException} is the supported way to
 * signal failure — the {@link McpToolAdapter} maps everything to client-safe output,
 * so unexpected exceptions never leak internals.</p>
 *
 * @param <P> the typed argument carrier; must be a Java record. The JSON input schema
 *            is generated from {@code P} by {@link ToolSchemas}, so schema and parser
 *            cannot drift.
 */
public interface Tool<P> {

    /** Unique tool name as exposed in tools/list (snake_case convention). */
    String name();

    /** One-line description shown to the calling LLM. */
    String description();

    /** The record type arguments are bound to. Never returns null. */
    Class<P> requestType();

    /**
     * Minimum capability the caller's role must cover. Default READ; override for
     * tools with a wider blast radius. Checked by the guard chain before execution.
     */
    default Permission requiredPermission() {
        return Permission.READ;
    }

    /** Execute the tool. Must be side-effect-light and deterministic in failure mode. */
    ToolResult execute(P args);
}
