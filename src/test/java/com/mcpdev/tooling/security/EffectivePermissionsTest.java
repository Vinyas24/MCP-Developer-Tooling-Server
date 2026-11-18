package com.mcpdev.tooling.security;

import java.util.List;
import java.util.Map;

import com.mcpdev.tooling.registry.Permission;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectivePermissionsTest {

    record Stub(String name, Permission permission) implements Tool<EffectivePermissionsTest.Stub.Args> {
        record Args() {
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public Class<Args> requestType() {
            return Args.class;
        }

        @Override
        public Permission requiredPermission() {
            return permission;
        }

        @Override
        public ToolResult execute(Args args) {
            return ToolResult.text("ok");
        }
    }

    Tool<?> tool(String name, Permission p) {
        return new Stub(name, p);
    }

    @Test
    @DisplayName("code floors: READ->VIEWER, QUERY->DEVELOPER, ADMIN->ADMIN")
    void floors() {
        var eff = new EffectivePermissions(new Tool<?>[]{
                tool("a_read", Permission.READ),
                tool("a_query", Permission.QUERY),
                tool("a_admin", Permission.ADMIN)}, Map.of());

        assertThat(eff.requiredRole("a_read")).isEqualTo(Role.VIEWER);
        assertThat(eff.requiredRole("a_query")).isEqualTo(Role.DEVELOPER);
        assertThat(eff.requiredRole("a_admin")).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("deny-by-default: unknown tool requires ADMIN")
    void unknownTool() {
        var eff = new EffectivePermissions(new Tool<?>[]{tool("known", Permission.READ)}, Map.of());
        assertThat(eff.requiredRole("ghost")).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("override may narrow (raise the floor)")
    void narrowingAllowed() {
        var eff = new EffectivePermissions(
                new Tool<?>[]{tool("a_read", Permission.READ)},
                Map.of("a_read", Role.DEVELOPER));
        assertThat(eff.requiredRole("a_read")).isEqualTo(Role.DEVELOPER);
    }

    @Test
    @DisplayName("override that would widen access fails startup")
    void wideningRejected() {
        assertThatThrownBy(() -> new EffectivePermissions(
                new Tool<?>[]{tool("a_query", Permission.QUERY)},
                Map.of("a_query", Role.VIEWER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WIDEN");
    }

    @Test
    @DisplayName("override for an unknown tool is a config typo error")
    void unknownOverrideRejected() {
        assertThatThrownBy(() -> new EffectivePermissions(
                new Tool<?>[]{tool("known", Permission.READ)},
                Map.of("kknown", Role.ADMIN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown tool");
    }

    @Test
    @DisplayName("role hierarchy: ordinal comparisons via meets()")
    void hierarchy() {
        assertThat(Role.VIEWER.meets(Role.VIEWER)).isTrue();
        assertThat(Role.DEVELOPER.meets(Role.VIEWER)).isTrue();
        assertThat(Role.VIEWER.meets(Role.DEVELOPER)).isFalse();
        assertThat(Role.ADMIN.meets(Role.ADMIN)).isTrue();
        assertThat(Role.DEVELOPER.meets(Role.ADMIN)).isFalse();
    }
}
