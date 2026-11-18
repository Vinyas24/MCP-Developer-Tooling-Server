package com.mcpdev.tooling.security;

/**
 * Transport-specific identity source. stdio implementation is config-driven; the HTTP
 * transport (Phase 6) provides its own resolver mapping authenticated sessions to roles.
 */
public interface IdentityResolver {

    Identity current();
}
