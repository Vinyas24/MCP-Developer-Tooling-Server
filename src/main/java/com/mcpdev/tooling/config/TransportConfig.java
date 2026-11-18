package com.mcpdev.tooling.config;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import com.mcpdev.tooling.executor.ToolExecutor;
import com.mcpdev.tooling.transport.HardenedStdioTransportProvider;

/**
 * Replaces the SDK's stock stdio transport (which Phase 1-5 used) with the hardened
 * one. Spring AI's autoconfiguration backs off on {@code @ConditionalOnMissingBean},
 * so this bean is what the MCP server is built on.
 *
 * <p>Only active in the default {@code stdio} mode. The {@code http} profile sets
 * {@code mcp.transport.mode=http} and uses Spring AI's Streamable-HTTP transport
 * instead (see {@code application-http.yml}).
 *
 * <p>The JSON mapper is the SAME bean the rest of the server uses, so transport and
 * tools agree on Jackson 3 semantics.
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp.transport", name = "mode",
        havingValue = "stdio", matchIfMissing = true)
public class TransportConfig {

    @Bean
    McpServerTransportProviderBase mcpServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper mcpServerJsonMapper,
            TransportProperties properties,
            ToolExecutor executor) {
        return new HardenedStdioTransportProvider(
                new JacksonMcpJsonMapper(mcpServerJsonMapper),
                System.in,
                System.out,
                properties.maxLineChars(),
                requestId -> executor.cancel(String.valueOf(requestId)));
    }
}
