package com.mcpdev.tooling.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mcpdev.tooling.integration.support.McpServerProcess;
import com.mcpdev.tooling.integration.support.PostgresFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security invariants at the boundary, black-box: the packaged server is spawned and
 * attacked over stdio. Unit tests prove the classes individually; these prove that the
 * WIRING preserves the invariants end to end.
 *
 * <p>Each invariant is attacked twice where it matters: through the tool surface and
 * around it (parser bypassed, role raised, config widened). One process per test —
 * role and override mistakes are startup configuration, and a shared process would
 * hide exactly those failures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityBoundaryIT {

    private Path base;
    private Path root;
    private Path outside;
    private Path serverJar;

    @BeforeAll
    void setUp() throws IOException {
        base = Files.createTempDirectory("mcp-security-it-");
        root = Files.createDirectories(base.resolve("root"));
        outside = Files.createDirectories(base.resolve("outside"));

        Files.writeString(root.resolve("hello.txt"), "inside content\n");
        Files.writeString(outside.resolve("secret.txt"), "sk-live-outside-secret\n");

        // A link that is lexically inside the root but resolves outside it.
        Files.createSymbolicLink(root.resolve("escape.txt"), outside.resolve("secret.txt"));

        serverJar = Path.of(System.getProperty("server.jar"));

        // Start the shared container once, before any test processes need it.
        PostgresFixture.container();
    }

    @AfterAll
    void tearDown() throws IOException {
        if (base == null || !Files.exists(base)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path newAuditFile(String name) {
        return base.resolve("audit-" + name + ".log");
    }

    private Map<String, String> env(String role, Path audit, boolean database) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MCP_ROLE", role);
        env.put("MCP_FS_ROOTS", root.toString());
        env.put("MCP_AUDIT_FILE", audit.toString());
        if (database) {
            PostgresFixture.applyServerEnv(env);
        } else {
            env.put("MCP_DB_URL", "");
        }
        return env;
    }

    private McpServerProcess start(String role, Path audit, boolean database,
                                   String... applicationArgs) throws IOException {
        McpServerProcess process = McpServerProcess.start(serverJar,
                env(role, audit, database), applicationArgs);
        process.initialize();
        return process;
    }

    private static JsonNode auditLine(McpServerProcess process, Path auditFile, String correlation)
            throws IOException {
        List<JsonNode> lines = McpServerProcess.auditLines(auditFile);
        for (JsonNode line : lines) {
            if (correlation.equals(line.get("correlation").asText())) {
                return line;
            }
        }
        throw new AssertionError("no audit line for correlation " + correlation
                + " in " + lines.size() + " lines");
    }

    private static String text(JsonNode callResult) {
        return callResult.get("result").get("content").get(0).get("text").asText();
    }

    private static long scalar(JsonNode callResult) {
        return Long.parseLong(callResult.get("result").get("structuredContent")
                .get("rows").get(0).get(0).asText());
    }

    @Test
    @DisplayName("dot-dot traversal is denied before any filesystem access")
    void dotDotTraversalDenied() throws IOException {
        Path audit = newAuditFile("traversal");
        try (McpServerProcess server = start("VIEWER", audit, false)) {
            JsonNode response = server.callTool("read_file",
                    Map.of("path", "../outside/secret.txt"));
            String correlation = response.get("id").asText();

            assertThat(response.get("result").get("isError").asBoolean()).isTrue();
            assertThat(text(response))
                    .contains("outside the allowed filesystem roots")
                    .doesNotContain("sk-live");

            assertThat(auditLine(server, audit, correlation).get("outcome").asText())
                    .isEqualTo("DENIED_DOMAIN");
            server.assertStdoutPure();
        }
    }

    @Test
    @DisplayName("absolute path outside every root is denied")
    void absoluteOutsidePathDenied() throws IOException {
        Path audit = newAuditFile("absolute");
        Path secret = outside.resolve("secret.txt");
        try (McpServerProcess server = start("VIEWER", audit, false)) {
            JsonNode response = server.callTool("read_file",
                    Map.of("path", secret.toAbsolutePath().toString()));
            String correlation = response.get("id").asText();

            assertThat(response.get("result").get("isError").asBoolean()).isTrue();
            assertThat(text(response)).contains("outside the allowed filesystem roots");
            assertThat(auditLine(server, audit, correlation).get("outcome").asText())
                    .isEqualTo("DENIED_DOMAIN");
        }
    }

    @Test
    @DisplayName("symlink escape is denied by the real-path layer")
    void symlinkEscapeDenied() throws IOException {
        Path audit = newAuditFile("symlink");
        try (McpServerProcess server = start("VIEWER", audit, false)) {
            JsonNode response = server.callTool("read_file", Map.of("path", "escape.txt"));
            String correlation = response.get("id").asText();

            assertThat(response.get("result").get("isError").asBoolean()).isTrue();
            assertThat(text(response))
                    .contains("escapes the allowed filesystem roots through symbolic links")
                    .doesNotContain("sk-live");
            assertThat(auditLine(server, audit, correlation).get("outcome").asText())
                    .isEqualTo("DENIED_DOMAIN");
        }
    }

    @Test
    @DisplayName("null byte in a path is denied, not passed to the OS")
    void nullByteDenied() throws IOException {
        Path audit = newAuditFile("nullbyte");
        try (McpServerProcess server = start("VIEWER", audit, false)) {
            JsonNode response = server.callTool("read_file",
                    Map.of("path", "hello.txt\u0000.png"));
            String correlation = response.get("id").asText();

            assertThat(response.get("result").get("isError").asBoolean()).isTrue();
            assertThat(auditLine(server, audit, correlation).get("outcome").asText())
                    .isEqualTo("DENIED_DOMAIN");
        }
    }

    @Test
    @DisplayName("privilege escalation: VIEWER cannot reach run_query")
    void viewerCannotQuery() throws IOException {
        Path audit = newAuditFile("viewer-query");
        try (McpServerProcess server = start("VIEWER", audit, true)) {
            JsonNode response = server.callTool("run_query",
                    Map.of("sql", "SELECT count(*) FROM app.users"));
            String correlation = response.get("id").asText();

            assertThat(response.get("result").get("isError").asBoolean()).isTrue();
            assertThat(text(response)).contains("requires role DEVELOPER");
            assertThat(auditLine(server, audit, correlation).get("outcome").asText())
                    .isEqualTo("DENIED_PERMISSION");
        }
    }

    @Test
    @DisplayName("SQL injection, stacked statements and data-modifying CTEs are denied")
    void sqlInjectionDenied() throws IOException {
        Path audit = newAuditFile("sqli");
        List<String> attacks = List.of(
                "DELETE FROM app.users",
                "SELECT 1; DELETE FROM app.users",
                "WITH gone AS (DELETE FROM app.users RETURNING *) SELECT * FROM gone",
                "SELECT * INTO app.pwned FROM app.users",
                "EXPLAIN ANALYZE DELETE FROM app.users",
                "SELECT * FROM app.users FOR UPDATE");

        try (McpServerProcess server = start("DEVELOPER", audit, true)) {
            for (String attack : attacks) {
                JsonNode response = server.callTool("run_query", Map.of("sql", attack));
                assertThat(response.get("result").get("isError").asBoolean())
                        .as("attack must be denied: " + attack).isTrue();
                assertThat(text(response)).as("denial is uniform: " + attack)
                        .startsWith("denied:");
                assertThat(auditLine(server, audit, response.get("id").asText())
                        .get("outcome").asText())
                        .as("audit outcome for: " + attack).isEqualTo("DENIED_DOMAIN");
            }

            // The same role CAN read: denial came from the gate, not a broken connection.
            JsonNode count = server.callTool("run_query",
                    Map.of("sql", "SELECT count(*) AS n FROM app.users"));
            assertThat(count.get("result").get("isError").asBoolean()).isFalse();
            assertThat(scalar(count)).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("startup fails loudly on an unknown role")
    void unknownRoleFailsStartup() throws IOException {
        Path audit = newAuditFile("unknown-role");
        McpServerProcess server = McpServerProcess.start(serverJar,
                env("SUPERUSER", audit, false));
        try {
            assertThat(server.awaitExit(java.time.Duration.ofSeconds(40))).isNotZero();
            assertThat(server.stderr()).contains("unknown role 'SUPERUSER'");
        } finally {
            server.close();
        }
    }

    @Test
    @DisplayName("startup fails on an override that would WIDEN access")
    void wideningOverrideFailsStartup() throws IOException {
        Path audit = newAuditFile("widening");
        McpServerProcess server = McpServerProcess.start(serverJar,
                env("DEVELOPER", audit, false),
                "--mcp.security.permission-overrides.[run_query]=VIEWER");
        try {
            assertThat(server.awaitExit(java.time.Duration.ofSeconds(40))).isNotZero();
            assertThat(server.stderr())
                    .contains("run_query")
                    .contains("would WIDEN");
        } finally {
            server.close();
        }
    }

    @Test
    @DisplayName("a narrowing override is honoured: ping=ADMIN denies a VIEWER")
    void narrowingOverrideDeniesViewer() throws IOException {
        Path audit = newAuditFile("narrowing");
        try (McpServerProcess server = start("VIEWER", audit, false,
                "--mcp.security.permission-overrides.[ping]=ADMIN")) {
            JsonNode response = server.callTool("ping", Map.of());
            String correlation = response.get("id").asText();

            assertThat(response.get("result").get("isError").asBoolean()).isTrue();
            assertThat(text(response)).contains("requires role ADMIN");
            assertThat(auditLine(server, audit, correlation).get("outcome").asText())
                    .isEqualTo("DENIED_PERMISSION");
        }
    }

    @Test
    @DisplayName("audit log stores argument hashes, never argument values")
    void auditStoresNoArgumentValues() throws IOException {
        Path audit = newAuditFile("no-values");
        String secret = "sk-live-very-secret-token";
        try (McpServerProcess server = start("VIEWER", audit, false)) {
            JsonNode response = server.callTool("echo", Map.of("echo", secret));
            assertThat(response.get("result").get("isError").asBoolean()).isFalse();

            String raw = Files.readString(audit, StandardCharsets.UTF_8);
            assertThat(raw).doesNotContain(secret).doesNotContain("sk-live");

            JsonNode line = auditLine(server, audit, response.get("id").asText());
            assertThat(line.get("argsSha256").asText()).matches("[0-9a-f]{16}");
        }
    }
}
