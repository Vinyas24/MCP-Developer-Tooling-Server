package com.mcpdev.tooling.security;

/**
 * Caller roles, ordered by capability. Ordinal IS the hierarchy — comparisons use
 * it directly; do not reorder.
 */
public enum Role {
    VIEWER("read-only: sandboxed FS reads and DB metadata"),
    DEVELOPER("everything VIEWER can do, plus run_query"),
    ADMIN("everything; reserved for side-effecting tools");

    private final String description;

    Role(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /** Hierarchy check: does this role meet the required one? */
    public boolean meets(Role required) {
        return ordinal() >= required.ordinal();
    }
}
