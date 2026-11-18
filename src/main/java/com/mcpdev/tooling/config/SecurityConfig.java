package com.mcpdev.tooling.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.security.AuditLog;
import com.mcpdev.tooling.security.DomainChecks;
import com.mcpdev.tooling.security.EffectivePermissions;
import com.mcpdev.tooling.security.Guard;
import com.mcpdev.tooling.security.GuardChain;
import com.mcpdev.tooling.security.Identity;
import com.mcpdev.tooling.security.IdentityGuard;
import com.mcpdev.tooling.security.IdentityResolver;
import com.mcpdev.tooling.security.PathSandbox;
import com.mcpdev.tooling.security.PermissionGuard;
import com.mcpdev.tooling.security.RateLimitGuard;
import com.mcpdev.tooling.security.Role;
import com.mcpdev.tooling.security.SizeLimitGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security wiring. Unknown role values fail STARTUP (misconfiguration must be loud,
 * never silently downgraded to a weaker role).
 */
@Configuration
public class SecurityConfig {

    @Bean
    PathSandbox pathSandbox(FileSystemProperties properties) {
        return new PathSandbox(properties.roots());
    }

    @Bean
    DomainChecks domainChecks(PathSandbox pathSandbox) {
        return new DomainChecks(pathSandbox);
    }

    @Bean
    IdentityResolver identityResolver(SecurityProperties properties) {
        Role role = parseRole(properties.role());
        return () -> new Identity("stdio-client", role);
    }

    @Bean
    AuditLog auditLog(SecurityProperties properties) {
        Path file = properties.auditFile().isEmpty()
                ? Path.of(System.getProperty("user.home"), ".mcp-dev-tooling", "audit.log")
                : Path.of(properties.auditFile());
        return new AuditLog(file);
    }

    @Bean
    EffectivePermissions effectivePermissions(List<Tool<?>> tools, SecurityProperties properties) {
        Map<String, Role> overrides = properties.permissionOverrides().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> parseRole(e.getValue())));
        return new EffectivePermissions(tools.toArray(Tool<?>[]::new), overrides);
    }

    @Bean
    GuardChain guardChain(IdentityResolver identityResolver,
                          EffectivePermissions effectivePermissions,
                          SecurityProperties properties) {
        return new GuardChain(List.of(
                new IdentityGuard(identityResolver),
                new PermissionGuard(effectivePermissions),
                new RateLimitGuard(properties.rateLimitCapacity(),
                        properties.rateLimitRefillPerSecond()),
                new SizeLimitGuard(properties.maxArgsBytes())));
    }

    static Role parseRole(String value) {
        if (value == null || value.isBlank()) {
            return Role.VIEWER;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unknown role '" + value
                    + "' — valid: VIEWER, DEVELOPER, ADMIN", e);
        }
    }
}
