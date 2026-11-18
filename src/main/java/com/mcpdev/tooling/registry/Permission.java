package com.mcpdev.tooling.registry;

/**
 * Capability level a tool demands from the caller's role. Part of the tool contract:
 * each tool declares its own floor via {@link Tool#requiredPermission()}.
 *
 * <ul>
 *   <li>READ — sandbox-confined or metadata-only reads (default)</li>
 *   <li>QUERY — arbitrary (validated) query surface</li>
 *   <li>ADMIN — reserved for anything with side effects beyond the sandbox</li>
 * </ul>
 */
public enum Permission {
    READ,
    QUERY,
    ADMIN
}
