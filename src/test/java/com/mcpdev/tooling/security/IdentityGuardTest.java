package com.mcpdev.tooling.security;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Identity is the first guard: fail closed when it cannot be established. */
class IdentityGuardTest {

    @Test
    @DisplayName("resolver returning null is a deny, not an NPE")
    void missingIdentityIsDenied() {
        GuardContext context = new GuardContext(
                GuardChainTest.namedTool("ping"), Map.of(), 0, "c1");
        Guard.Result result = new IdentityGuard(() -> null).check(context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.outcome()).isEqualTo(AuditLog.Outcome.DENIED_IDENTITY);
        assertThat(result.clientMessage()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("identity with a null role is a deny")
    void missingRoleIsDenied() {
        GuardContext context = new GuardContext(
                GuardChainTest.namedTool("ping"), Map.of(), 0, "c2");
        Guard.Result result = new IdentityGuard(() -> new Identity("anonymous", null))
                .check(context);

        assertThat(result.allowed()).isFalse();
        assertThat(result.outcome()).isEqualTo(AuditLog.Outcome.DENIED_IDENTITY);
    }

    @Test
    @DisplayName("a resolved identity is attached to the context")
    void identityIsAttached() {
        GuardContext context = new GuardContext(
                GuardChainTest.namedTool("ping"), Map.of(), 0, "c3");
        Guard.Result result = new IdentityGuard(() -> Identity.of("stdio-client", Role.ADMIN))
                .check(context);

        assertThat(result.allowed()).isTrue();
        assertThat(context.identity().name()).isEqualTo("stdio-client");
        assertThat(context.identity().role()).isEqualTo(Role.ADMIN);
    }
}
