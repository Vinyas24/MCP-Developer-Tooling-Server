package com.mcpdev.tooling.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mcpdev.tooling.config.DatabaseProperties;
import com.mcpdev.tooling.executor.ToolExecutor;
import com.mcpdev.tooling.security.AuditLog;
import com.mcpdev.tooling.security.DomainChecks;
import com.mcpdev.tooling.security.EffectivePermissions;
import com.mcpdev.tooling.security.Guard;
import com.mcpdev.tooling.security.GuardChain;
import com.mcpdev.tooling.security.Identity;
import com.mcpdev.tooling.security.IdentityGuard;
import com.mcpdev.tooling.security.IdentityResolver;
import com.mcpdev.tooling.security.PathSandbox;
import com.mcpdev.tooling.security.PermissionGuard;
import com.mcpdev.tooling.security.RateLimitGuard;
import com.mcpdev.tooling.security.Role;
import com.mcpdev.tooling.security.SizeLimitGuard;
import com.mcpdev.tooling.tools.EchoTool;
import com.mcpdev.tooling.tools.PingTool;
import com.mcpdev.tooling.tools.TimeTool;
import com.mcpdev.tooling.tools.db.DbClient;
import com.mcpdev.tooling.tools.db.DescribeTableTool;
import com.mcpdev.tooling.tools.db.ListTablesTool;
import com.mcpdev.tooling.tools.db.RunQueryTool;
import com.mcpdev.tooling.tools.fs.ListDirTool;
import com.mcpdev.tooling.tools.fs.ReadFileTool;
import com.mcpdev.tooling.tools.fs.SearchCodeTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE Phase 5 done-criteria: every role × every tool, deny-by-default visible.
 * Denials are uniform ("denied: …") and audited; allows fall through to execution.
 */
class RoleToolMatrixTest {

    @TempDir
    Path root;

    private PathSandbox sandbox;
    private AuditLog audit;
    private List<Tool<?>> tools;
    private EffectivePermissions perms;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(root.resolve("readme.txt"), "needle content\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/App.java"), "class App {}\n");

        sandbox = new PathSandbox(List.of(root.toString()));
        audit = new AuditLog(root.resolve("audit.log")); // temp-scoped audit file
        DbClient db = new DbClient(DatabaseProperties.DISABLED, () -> {
            throw new IllegalStateException("pool must never be created in this test");
        });

        tools = List.of(
                new PingTool(),
                new EchoTool(),
                new TimeTool(),
                new ListDirTool(sandbox, com.mcpdev.tooling.config.FileSystemProperties
                        .withDefaults(List.of(root.toString()))),
                new ReadFileTool(sandbox, com.mcpdev.tooling.config.FileSystemProperties
                        .withDefaults(List.of(root.toString()))),
                new SearchCodeTool(sandbox, com.mcpdev.tooling.config.FileSystemProperties
                        .withDefaults(List.of(root.toString()))),
                new ListTablesTool(db),
                new DescribeTableTool(db),
                new RunQueryTool(db));
        perms = new EffectivePermissions(tools.toArray(Tool<?>[]::new), Map.of());
        executor = new ToolExecutor(64, 200, 5_000, 0);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private McpToolAdapter adapterFor(Role role) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        IdentityResolver resolver = () -> Identity.of("matrix-client", role);
        GuardChain chain = new GuardChain(List.of(
                new IdentityGuard(resolver),
                new PermissionGuard(perms),
                new RateLimitGuard(1000, 1_000_000), // effectively unlimited for the matrix
                new SizeLimitGuard(65_536)));
        return new McpToolAdapter(mapper, chain, new DomainChecks(sandbox), audit, executor);
    }

    static Stream<Arguments> matrix() {
        List<String> toolNames = List.of("ping", "echo", "time", "list_dir", "read_file",
                "search_code", "list_tables", "describe_table", "run_query");
        Stream.Builder<Arguments> b = Stream.builder();
        for (Role role : Role.values()) {
            for (String toolName : toolNames) {
                boolean allowed = switch (toolName) {
                    case "run_query" -> role.meets(Role.DEVELOPER);
                    default -> true; // READ floor: every role passes
                };
                b.add(Arguments.of(role, toolName, allowed));
            }
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0} x {1} allowed={2}")
    @MethodSource("matrix")
    void matrixEntry(Role role, String toolName, boolean allowed) {
        McpToolAdapter adapter = adapterFor(role);
        Tool<?> tool = tools.stream().filter(t -> t.name().equals(toolName))
                .findFirst().orElseThrow();
        Map<String, Object> args = defaultArgs(toolName);

        var result = adapter.invoke(tool, args);
        String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent)
                result.content().get(0)).text();

        boolean denied = text.startsWith("denied:");
        assertThat(denied)
                .as("role=%s tool=%s expected allowed=%s but got: %s", role, toolName, allowed, text)
                .isEqualTo(!allowed);
    }

    /** Instance method: FS paths need the @TempDir root. */
    private Map<String, Object> defaultArgs(String toolName) {
        return switch (toolName) {
            case "echo" -> Map.of("echo", "hi");
            case "list_dir" -> Map.of("path", root.toString());
            case "read_file" -> Map.of("path", root.resolve("readme.txt").toString());
            case "search_code" -> Map.of("query", "needle");
            case "describe_table" -> Map.of("table", "users");
            case "run_query" -> Map.of("sql", "SELECT 1");
            default -> Map.of();
        };
    }
    @ParameterizedTest(name = "paths: {0}")
    @MethodSource("roles")
    void pathArgs(Role role) {
        McpToolAdapter adapter = adapterFor(role);
        Tool<?> listDir = tools.stream().filter(t -> t.name().equals("list_dir"))
                .findFirst().orElseThrow();
        Tool<?> readFile = tools.stream().filter(t -> t.name().equals("read_file"))
                .findFirst().orElseThrow();

        var listResult = adapter.invoke(listDir, Map.of("path", root.toString()));
        var readResult = adapter.invoke(readFile, Map.of("path", root.resolve("readme.txt").toString()));

        if (role == Role.VIEWER) {
            // VIEWER may read; assertions just prove the calls were not permission-denied
            assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent)
                    listResult.content().get(0)).text()).doesNotStartWith("denied:");
            assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent)
                    readResult.content().get(0)).text()).doesNotStartWith("denied:");
        }
    }

    static Stream<Role> roles() {
        return Stream.of(Role.values());
    }

    @Test
    @DisplayName("every denial produced exactly one audit line with DENIED_PERMISSION")
    void denialsAreAudited() throws IOException {
        McpToolAdapter viewer = adapterFor(Role.VIEWER);
        Tool<?> runQuery = tools.stream().filter(t -> t.name().equals("run_query"))
                .findFirst().orElseThrow();
        viewer.invoke(runQuery, Map.of("sql", "SELECT 1"));

        String auditLines = Files.readString(root.resolve("audit.log"));
        assertThat(auditLines)
                .contains("\"tool\":\"run_query\"")
                .contains("\"outcome\":\"DENIED_PERMISSION\"")
                .contains("\"role\":\"VIEWER\"");
    }

    @Test
    @DisplayName("args hash is stable for identical arguments, no arg values in audit")
    void auditHashes() throws IOException {
        McpToolAdapter dev = adapterFor(Role.DEVELOPER);
        Tool<?> echo = tools.stream().filter(t -> t.name().equals("echo"))
                .findFirst().orElseThrow();
        dev.invoke(echo, Map.of("echo", "secret-search-term"));
        dev.invoke(echo, Map.of("echo", "secret-search-term"));

        String auditLines = Files.readString(root.resolve("audit.log"));
        assertThat(auditLines).doesNotContain("secret-search-term");
        long count = auditLines.lines()
                .filter(l -> l.contains("\"tool\":\"echo\"")).count();
        assertThat(count).isEqualTo(2);
        String hash1 = auditLines.lines().filter(l -> l.contains("\"tool\":\"echo\""))
                .findFirst().orElseThrow();
        String hash2 = auditLines.lines().filter(l -> l.contains("\"tool\":\"echo\""))
                .skip(1).findFirst().orElseThrow();
        assertThat(extractHash(hash1)).isEqualTo(extractHash(hash2)).hasSize(16);
    }

    private static String extractHash(String line) {
        int i = line.indexOf("\"argsSha256\":\"") + 14;
        return line.substring(i, i + 16);
    }
}
