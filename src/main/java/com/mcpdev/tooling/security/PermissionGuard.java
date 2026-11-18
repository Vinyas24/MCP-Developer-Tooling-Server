package com.mcpdev.tooling.security;

import com.mcpdev.tooling.registry.Tool;

/**
 * Role gate against the effective (floor + narrow-only overrides) permission map.
 * Deny-by-default: any tool without an entry requires ADMIN.
 */
public class PermissionGuard implements Guard {

    private final EffectivePermissions permissions;

    public PermissionGuard(EffectivePermissions permissions) {
        this.permissions = permissions;
    }

    @Override
    public Result check(GuardContext context) {
        Tool<?> tool = context.tool();
        Role required = permissions.requiredRole(tool.name());
        Role actual = context.identity().role();
        if (!actual.meets(required)) {
            return Result.deny(AuditLog.Outcome.DENIED_PERMISSION,
                    "denied: tool '" + tool.name() + "' requires role " + required
                            + ", caller has " + actual);
        }
        return Result.allow();
    }
}
