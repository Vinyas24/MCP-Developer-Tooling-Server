package com.mcpdev.tooling.registry;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.mcp.customizer.McpAsyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bridges internal {@link Tool}s to the SDK as bean-provided ASYNC specifications.
 * The Spring AI autoconfiguration consumes these when it BUILDS the MCP server, so
 * tool registration happens before any session exists — no startup
 * {@code notifications/tools/list_changed} chatter.
 *
 * <p>Async (Phase 6) rather than sync: the SDK's sync bridge runs tool code on
 * {@code boundedElastic} platform threads and cannot express deadlines or
 * cancellation. The async bridge lets {@link McpToolAdapter} own both.
 */
@Configuration
public class ToolRegistrar {

    @Bean
    List<McpServerFeatures.AsyncToolSpecification> toolSpecifications(
            List<Tool<?>> tools, McpToolAdapter adapter, ToolRegistry registry) {

        tools.forEach(registry::register);

        return tools.stream()
                .map(adapter::toSpecification)
                .toList();
    }

    /**
     * The SDK validates call arguments against the advertised JSON schema before
     * dispatching to a tool handler — which means a schema-invalid call never reaches
     * our funnel and is therefore never audited. Our binder enforces the same shape
     * (strict, unknown properties rejected) INSIDE the funnel, so every call gets its
     * one audit line and the client still gets a clear INVALID_ARGS error.
     *
     * <p>Unknown tool names remain SDK-handled ({@code -32602}) — there is no
     * per-request seam for a name that maps to no specification. Documented gap.
     */
    @Bean
    McpAsyncServerCustomizer toolInputValidationOwnedByFunnel() {
        return specification -> specification.validateToolInputs(false);
    }
}
