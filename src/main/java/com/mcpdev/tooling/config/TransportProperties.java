package com.mcpdev.tooling.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * stdio transport limits. {@code maxLineChars} caps a single inbound JSON-RPC line
 * before allocation — a hostile or buggy peer cannot make the server buffer without
 * bound.
 */
@ConfigurationProperties(prefix = "mcp.transport")
public record TransportProperties(int maxLineChars) {

    public TransportProperties {
        if (maxLineChars <= 0) maxLineChars = 1_048_576;
    }
}
