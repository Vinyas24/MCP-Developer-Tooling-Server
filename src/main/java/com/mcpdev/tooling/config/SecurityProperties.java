package com.mcpdev.tooling.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security configuration. role: the stdio caller's role (env MCP_ROLE; VIEWER default).
 * permissionOverrides: tool -> role, may only NARROW (raising the requirement) —
 * widening is rejected at startup by EffectivePermissions.
 */
@ConfigurationProperties(prefix = "mcp.security")
public record SecurityProperties(
        String role,
        Map<String, String> permissionOverrides,
        double rateLimitCapacity,
        double rateLimitRefillPerSecond,
        int maxArgsBytes,
        String auditFile) {

    public SecurityProperties {
        role = role == null || role.isBlank() ? "VIEWER" : role.trim().toUpperCase();
        permissionOverrides = permissionOverrides == null ? Map.of() : Map.copyOf(permissionOverrides);
        if (rateLimitCapacity <= 0) rateLimitCapacity = 30;
        if (rateLimitRefillPerSecond <= 0) rateLimitRefillPerSecond = 10;
        if (maxArgsBytes <= 0) maxArgsBytes = 65_536;
        auditFile = auditFile == null ? "" : auditFile.trim();
    }
}
