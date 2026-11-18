package com.mcpdev.tooling.security;

/**
 * Raw arguments size cap (serialized JSON length). Runs before binding: no CPU is
 * spent parsing oversized payloads, and Jackson's per-value limits (if enabled later)
 * are not the first line of defense.
 */
public class SizeLimitGuard implements Guard {

    private final int maxArgsBytes;

    public SizeLimitGuard(int maxArgsBytes) {
        this.maxArgsBytes = maxArgsBytes;
    }

    @Override
    public Result check(GuardContext context) {
        if (context.rawArgsBytes() < 0 || context.rawArgsBytes() > maxArgsBytes) {
            return Result.deny(AuditLog.Outcome.DENIED_SIZE,
                    "denied: arguments exceed the " + maxArgsBytes + " byte limit");
        }
        return Result.allow();
    }
}
