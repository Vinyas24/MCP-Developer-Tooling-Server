package com.mcpdev.tooling.security;

import com.mcpdev.tooling.registry.Permission;
import com.mcpdev.tooling.registry.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Code-owned floor: tool's {@link Permission} maps to a minimum Role. Deployment
 * config may only NARROW (raise) the requirement per tool — an override that would
 * widen access fails startup. Deny-by-default is structural: any tool not in the
 * map requires ADMIN.
 */
public final class EffectivePermissions {

    private final Map<String, Role> requiredRoles = new LinkedHashMap<>();

    public EffectivePermissions(Tool<?>[] tools, Map<String, Role> narrowOnlyOverrides) {
        for (Tool<?> tool : tools) {
            requiredRoles.put(tool.name(), roleFor(tool.requiredPermission()));
        }
        // unknown tool names in overrides are a config error — typos must not pass silently
        for (Map.Entry<String, Role> entry : narrowOnlyOverrides.entrySet()) {
            Role floor = requiredRoles.get(entry.getKey());
            if (floor == null) {
                throw new IllegalStateException("permission override for unknown tool: "
                        + entry.getKey());
            }
            if (!entry.getValue().meets(floor)) {
                throw new IllegalStateException("permission override for '" + entry.getKey()
                        + "' would WIDEN access (" + entry.getValue() + " < floor " + floor
                        + ") — config may only narrow");
            }
            requiredRoles.put(entry.getKey(), entry.getValue());
        }
    }

    public static Role roleFor(Permission permission) {
        return switch (permission) {
            case READ -> Role.VIEWER;
            case QUERY -> Role.DEVELOPER;
            case ADMIN -> Role.ADMIN;
        };
    }

    public Role requiredRole(String toolName) {
        return requiredRoles.getOrDefault(toolName, Role.ADMIN);
    }

    public Map<String, Role> asMap() {
        return Map.copyOf(requiredRoles);
    }
}
