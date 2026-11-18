package com.mcpdev.tooling.security;

import java.util.List;
import java.util.Map;

import com.mcpdev.tooling.registry.Permission;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardChainTest {

    record Args() {
    }

    static Tool<Args> namedTool(String name) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public Class<Args> requestType() {
                return Args.class;
            }

            @Override
            public ToolResult execute(Args args) {
                return ToolResult.text("ok");
            }
        };
    }

    @Test
    @DisplayName("chain order: identity failure short-circuits later guards")
    void shortCircuit() {
        Guard later = Mockito.mock(Guard.class);
        when(later.check(any())).thenReturn(Guard.Result.allow());

        GuardChain chain = new GuardChain(List.of(
                ctx -> Guard.Result.deny(AuditLog.Outcome.DENIED_IDENTITY, "no identity"),
                later));

        var ctx = new GuardContext(namedTool("t"), Map.of(), 2, "corr");
        Guard.Result result = chain.check(ctx);

        assertThat(result.allowed()).isFalse();
        assertThat(result.outcome()).isEqualTo(AuditLog.Outcome.DENIED_IDENTITY);
        verify(later, never()).check(any());
    }

    @Test
    @DisplayName("identity guard attaches identity; permission guard enforces the floor")
    void identityThenPermission() {
        // the permission map is built from the SAME tool instance that gets invoked —
        // mirroring production (ToolRegistrar feeds both from one List<Tool<?>>)
        Tool<Args> queryTool = new Tool<>() {
            @Override
            public String name() {
                return "query_tool";
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public Class<Args> requestType() {
                return Args.class;
            }

            @Override
            public Permission requiredPermission() {
                return Permission.QUERY;
            }

            @Override
            public ToolResult execute(Args args) {
                return ToolResult.text("ok");
            }
        };
        EffectivePermissions perms = new EffectivePermissions(
                new Tool<?>[]{queryTool}, Map.of());

        IdentityResolver viewer = () -> Identity.of("t", Role.VIEWER);
        IdentityResolver developer = () -> Identity.of("t", Role.DEVELOPER);

        GuardChain viewerChain = new GuardChain(List.of(
                new IdentityGuard(viewer), new PermissionGuard(perms)));
        GuardChain devChain = new GuardChain(List.of(
                new IdentityGuard(developer), new PermissionGuard(perms)));

        var ctxViewer = new GuardContext(queryTool, Map.of(), 2, "c1");
        assertThat(viewerChain.check(ctxViewer).outcome())
                .isEqualTo(AuditLog.Outcome.DENIED_PERMISSION);

        var ctxDev = new GuardContext(queryTool, Map.of(), 2, "c2");
        assertThat(devChain.check(ctxDev).allowed()).isTrue();
        assertThat(ctxDev.identity().role()).isEqualTo(Role.DEVELOPER);
    }

    @Test
    @DisplayName("rate limit: burst over capacity denies with DENIED_RATE")
    void rateLimit() {
        RateLimitGuard guard = new RateLimitGuard(2, 0.0001); // effectively no refill in-test
        var ctx = new GuardContext(namedTool("t"), Map.of(), 2, "c");
        ctx.identity(Identity.of("client-1", Role.VIEWER));

        assertThat(guard.check(ctx).allowed()).isTrue();
        assertThat(guard.check(ctx).allowed()).isTrue();
        Guard.Result third = guard.check(ctx);
        assertThat(third.allowed()).isFalse();
        assertThat(third.outcome()).isEqualTo(AuditLog.Outcome.DENIED_RATE);

        // buckets are per identity: another client is unaffected
        var ctx2 = new GuardContext(namedTool("t"), Map.of(), 2, "c");
        ctx2.identity(Identity.of("client-2", Role.VIEWER));
        assertThat(guard.check(ctx2).allowed()).isTrue();
    }

    @Test
    @DisplayName("size limit denies oversized raw args before binding")
    void sizeLimit() {
        SizeLimitGuard guard = new SizeLimitGuard(100);
        var big = new GuardContext(namedTool("t"), Map.of(), 101, "c");
        var ok = new GuardContext(namedTool("t"), Map.of(), 100, "c");

        assertThat(guard.check(ok).allowed()).isTrue();
        assertThat(guard.check(big).outcome()).isEqualTo(AuditLog.Outcome.DENIED_SIZE);
    }
}
