package com.mcpdev.tooling.registry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

import com.mcpdev.tooling.executor.ToolExecutor;
import com.mcpdev.tooling.security.AuditLog;
import com.mcpdev.tooling.security.DomainChecks;
import com.mcpdev.tooling.security.EffectivePermissions;
import com.mcpdev.tooling.security.Guard;
import com.mcpdev.tooling.security.GuardChain;
import com.mcpdev.tooling.security.Identity;
import com.mcpdev.tooling.security.IdentityGuard;
import com.mcpdev.tooling.security.IdentityResolver;
import com.mcpdev.tooling.security.PermissionGuard;
import com.mcpdev.tooling.security.RateLimitGuard;
import com.mcpdev.tooling.security.Role;
import com.mcpdev.tooling.security.SizeLimitGuard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adapter behavior is the client-facing error contract: expected failures become
 * isError=true results with client-safe text; unexpected ones are wiped of all detail.
 */
class McpToolAdapterTest {

    @TempDir
    Path tempDir;

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private McpToolAdapter adapter;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        // error-mapping tests: identity is ADMIN so permission gating never interferes
        IdentityResolver admin = () -> Identity.of("test-client", Role.ADMIN);
        EffectivePermissions perms = new EffectivePermissions(new Tool<?>[0], java.util.Map.of());
        GuardChain chain = new GuardChain(List.of(
                new IdentityGuard(admin),
                new PermissionGuard(perms),
                new RateLimitGuard(1000, 1_000_000),
                new SizeLimitGuard(65_536)));
        // no Path-bearing tools here -> disabled sandbox never consulted
        executor = new ToolExecutor(64, 200, 5_000, 0);
        adapter = new McpToolAdapter(mapper, chain,
                new DomainChecks(new com.mcpdev.tooling.security.PathSandbox(List.of())),
                new AuditLog(tempDir.resolve("audit.log")), executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    // --- fixtures -----------------------------------------------------------

    static class UnknownException extends RuntimeException {
        UnknownException() {
            super("db password is hunter2");
        }
    }

    record FailingToolArgs() {
    }

    Tool<FailingToolArgs> failingTool() {
        return new Tool<>() {
            public String name() {
                return "failing";
            }

            public String description() {
                return "d";
            }

            public Class<FailingToolArgs> requestType() {
                return FailingToolArgs.class;
            }

            public ToolResult execute(FailingToolArgs args) {
                throw new UnknownException();
            }
        };
    }

    static record GreetingArgs(String name, Optional<Integer> shout) {
    }

    Tool<GreetingArgs> greetingTool() {
        return new Tool<>() {
            public String name() {
                return "greet";
            }

            public String description() {
                return "d";
            }

            public Class<GreetingArgs> requestType() {
                return GreetingArgs.class;
            }

            public ToolResult execute(GreetingArgs args) {
                return ToolResult.text("hi " + args.name());
            }
        };
    }

    // --- happy path ---------------------------------------------------------

    @Test
    @DisplayName("valid args bind to record and return success result")
    void happyPath() {
        var result = adapter.invoke(greetingTool(), Map.of("name", "ada"));

        assertThat(result.isError()).isFalse();
        assertThat(result.content().get(0)).hasFieldOrPropertyWithValue("text", "hi ada");
    }

    @Test
    @DisplayName("null arguments map becomes empty record binding")
    void nullArguments() {
        var result = adapter.invoke(failingTool(), null);

        // failingTool throws UnknownException -> internal path, proves null was bound fine
        assertThat(result.isError()).isTrue();
        String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content()
                .get(0)).text();
        assertThat(text).startsWith("Internal error in tool 'failing' (correlation=");
    }

    // --- mapping contract ---------------------------------------------------

    @Test
    @DisplayName("unknown key in arguments -> isError result, rejected binding")
    void unknownKeyRejected() {
        var result = adapter.invoke(greetingTool(), Map.of("name", "ada", "evil", "x"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("missing required component -> isError result")
    void missingRequired() {
        var result = adapter.invoke(greetingTool(), Map.of());

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("expected domain error keeps its client-safe message")
    void expectedError() {
        record Empty() {
        }
        Tool<Empty> tool = new Tool<>() {
            public String name() {
                return "dom";
            }

            public String description() {
                return "d";
            }

            public Class<Empty> requestType() {
                return Empty.class;
            }

            public ToolResult execute(Empty args) {
                throw new ToolExecutionException("file is too large");
            }
        };
        var result = adapter.invoke(tool, Map.of());

        assertThat(result.isError()).isTrue();
        String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content()
                .get(0)).text();
        assertThat(text).isEqualTo("file is too large");
    }

    @Test
    @DisplayName("unexpected exception: message scrubbed, correlation id present, no exception text")
    void unexpectedErrorScrubbed() {
        var result = adapter.invoke(failingTool(), Map.of());

        assertThat(result.isError()).isTrue();
        String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent) result.content()
                .get(0)).text();
        assertThat(text)
                .startsWith("Internal error in tool 'failing' (correlation=")
                .doesNotContain("hunter2")
                .doesNotContain("UnknownException");
    }

    @Test
    @DisplayName("specification exposes schema generated from the record")
    void specification() {
        var spec = adapter.toSpecification(greetingTool());

        assertThat(spec.tool().name()).isEqualTo("greet");
        assertThat(spec.tool().inputSchema()).isNotNull();
        assertThat(spec.tool().annotations().readOnlyHint()).isTrue();
        assertThat(spec.tool().annotations().destructiveHint()).isFalse();
    }

    @Test
    @DisplayName("spec bean registration feeds registry and produces one spec per tool")
    void registrar() throws IOException {
        var registry = new ToolRegistry();
        IdentityResolver viewer = () -> Identity.of("t", Role.VIEWER);
        var chain = new GuardChain(List.of(
                new IdentityGuard(viewer)));
        var domainChecks = new DomainChecks(new com.mcpdev.tooling.security.PathSandbox(List.of()));
        var audit = new AuditLog(tempDir.resolve("audit.log"));
        var adapter = new McpToolAdapter(mapper, chain, domainChecks, audit,
                new ToolExecutor(64, 200, 5_000, 0));
        var registrar = new ToolRegistrar();

        var specs = registrar.toolSpecifications(
                java.util.List.of(greetingTool(), failingTool()), adapter, registry);

        assertThat(specs).hasSize(2);
        assertThat(registry.all()).hasSize(2);
        assertThat(specs.get(0).tool().name()).isEqualTo("greet");
    }

    @Test
    @DisplayName("duplicate tool name is a startup failure, not silent shadowing")
    void duplicateNames() {
        var registry = new ToolRegistry();
        registry.register(greetingTool());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> registry.register(greetingTool()));
    }
}
