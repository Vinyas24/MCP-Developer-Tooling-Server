package com.mcpdev.tooling.security;

/** Resolves and attaches the caller identity. First guard in the chain. */
public class IdentityGuard implements Guard {

    private final IdentityResolver resolver;

    public IdentityGuard(IdentityResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Result check(GuardContext context) {
        Identity identity = resolver.current();
        if (identity == null || identity.role() == null) {
            return Result.deny(AuditLog.Outcome.DENIED_IDENTITY,
                    "denied: caller identity could not be established");
        }
        context.identity(identity);
        return Result.allow();
    }
}
