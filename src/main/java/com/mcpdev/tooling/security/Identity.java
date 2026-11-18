package com.mcpdev.tooling.security;

/**
 * Who is calling. MCP carries no identity in the protocol — the role is a deployment
 * decision (stdio: config/env; HTTP later: authenticated session). Never derived from
 * spoofable data like clientInfo.
 */
public record Identity(String name, Role role) {

    public static Identity of(String name, Role role) {
        return new Identity(name, role);
    }
}
