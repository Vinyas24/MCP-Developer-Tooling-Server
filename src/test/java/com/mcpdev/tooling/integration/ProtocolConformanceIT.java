package com.mcpdev.tooling.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.mcpdev.tooling.integration.support.McpServerProcess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol conformance, black-box: the packaged server is spawned and driven over
 * stdio exactly as Claude Desktop would.
 *
 * <p>What this class exists to prove (and unit tests cannot):
 * <ul>
 *   <li>the handshake negotiates 2025-11-25 and advertises the expected server info</li>
 *   <li>malformed JSON, unknown methods and unknown tools get the JSON-RPC error codes
 *       the spec assigns, and the session SURVIVES each of them</li>
 *   <li>notifications get no response, string ids are echoed verbatim</li>
 *   <li>stdout carries JSON-RPC only — logging never leaks into the protocol stream</li>
 * </ul>
 *
 * <p>One process for the whole class: startup costs seconds and the tests are
 * independent (unique ids, no shared mutable tool state).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtocolConformanceIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "ping", "echo", "time", "list_dir", "read_file", "search_code",
            "list_tables", "describe_table", "run_query");

    private Path root;
    private Path auditFile;
    private McpServerProcess server;

    @BeforeAll
    void startServer() throws IOException {
        root = Files.createTempDirectory("mcp-protocol-it-");
        Files.writeString(root.resolve("hello.txt"), "hello\n");
        auditFile = root.resolve("audit.log");

        Map<String, String> env = new LinkedHashMap<>();
        env.put("MCP_ROLE", "VIEWER");
        env.put("MCP_FS_ROOTS", root.toString());
        env.put("MCP_AUDIT_FILE", auditFile.toString());
        env.put("MCP_DB_URL", ""); // DB tools advertised, execution disabled

        server = McpServerProcess.start(
                Path.of(System.getProperty("server.jar")), env);
        server.initialize();
    }

    @AfterAll
    void stopServer() {
        if (server != null) {
            server.assertStdoutPure();
            server.close();
        }
    }

    @Test
    @DisplayName("handshake negotiates 2025-11-25 and identifies the server")
    void handshake() {
        // initialize() already completed in @BeforeAll; repeat it to inspect the result
        // body (a second initialize is legal for a fresh request id).
        JsonNode result = server.call("initialize", Map.of(
                "protocolVersion", "2025-11-25",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mcp-it", "version", "0")));
        JsonNode body = result.get("result");

        assertThat(body.get("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(body.get("serverInfo").get("name").asText()).isEqualTo("mcp-dev-tooling");
        assertThat(body.get("serverInfo").get("version").asText()).isEqualTo("0.1.0");
        assertThat(body.get("capabilities").has("tools")).isTrue();
    }

    @Test
    @DisplayName("tools/list advertises every tool once, read-only and non-destructive")
    void toolsList() {
        JsonNode tools = server.call("tools/list", null).get("result").get("tools");

        List<String> names = tools.valueStream()
                .map(t -> t.get("name").asText())
                .toList();
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
        assertThat(names).doesNotHaveDuplicates();

        for (JsonNode tool : tools) {
            assertThat(tool.get("description").asText()).isNotBlank();
            assertThat(tool.get("inputSchema").get("type").asText()).isEqualTo("object");
            JsonNode annotations = tool.get("annotations");
            assertThat(annotations.get("readOnlyHint").asBoolean())
                    .as("readOnlyHint for " + tool.get("name").asText()).isTrue();
            assertThat(annotations.get("destructiveHint").asBoolean())
                    .as("destructiveHint for " + tool.get("name").asText()).isFalse();
        }

        JsonNode echo = toolByName(tools, "echo");
        assertThat(echo.get("inputSchema").get("required").valueStream()
                .map(JsonNode::asText).toList()).containsExactly("echo");

        JsonNode runQuery = toolByName(tools, "run_query");
        assertThat(runQuery.get("inputSchema").get("required").valueStream()
                .map(JsonNode::asText).toList()).containsExactly("sql");
    }

    @Test
    @DisplayName("protocol ping answers with an empty result and the session stays up")
    void protocolPing() {
        JsonNode response = server.call("ping", null);

        assertThat(response.has("error")).isFalse();
        assertThat(response.get("result").isObject()).isTrue();
        assertThat(server.isAlive()).isTrue();
    }

    @Test
    @DisplayName("malformed JSON gets -32700 with id:null and does not kill the session")
    void malformedJsonDoesNotKillTheSession() {
        JsonNode error = server.sendRaw("{ this is not json");

        assertThat(error.get("id").isNull()).isTrue();
        assertThat(error.get("error").get("code").asInt()).isEqualTo(-32700);
        assertThat(error.get("error").get("message").asText()).isEqualTo("Parse error");

        // same process, still serving
        assertThat(server.callTool("ping", Map.of()).get("result").get("isError").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("batch arrays are rejected (not supported) without ending the session")
    void batchArrayRejected() {
        JsonNode error = server.sendRaw(
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");

        assertThat(error.get("error").get("code").asInt()).isEqualTo(-32700);

        assertThat(server.callTool("ping", Map.of()).get("result").get("isError").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("unknown method maps to -32601 and the session stays up")
    void unknownMethod() {
        JsonNode response = server.call("does/not/exist", Map.of());

        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(server.isAlive()).isTrue();
    }

    @Test
    @DisplayName("unknown tool maps to -32602 (SDK-handled, documented gap)")
    void unknownTool() {
        JsonNode response = server.callTool("no_such_tool", Map.of());

        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(server.isAlive()).isTrue();
    }

    @Test
    @DisplayName("missing required argument is a tool error, audited as INVALID_ARGS")
    void missingRequiredArgumentIsAudited() throws IOException {
        JsonNode response = server.callTool("echo", Map.of());
        String correlation = response.get("id").asText();

        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        assertThat(response.get("result").get("content").get(0).get("text").asText())
                .contains("Missing required argument")
                .contains("echo");

        JsonNode line = auditLineFor(correlation);
        assertThat(line.get("tool").asText()).isEqualTo("echo");
        assertThat(line.get("outcome").asText()).isEqualTo("INVALID_ARGS");
        assertThat(line.get("role").asText()).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("string request ids are echoed verbatim")
    void stringRequestIdIsEchoed() {
        JsonNode response = server.sendRaw(
                "{\"jsonrpc\":\"2.0\",\"id\":\"client-abc\",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"echo\",\"arguments\":{\"echo\":\"hi\"}}}");

        assertThat(response.get("id").asText()).isEqualTo("client-abc");
        assertThat(response.get("result").get("content").get(0).get("text").asText())
                .isEqualTo("hi");
    }

    @Test
    @DisplayName("notifications produce no response at all")
    void notificationsProduceNoResponse() {
        // Cancelling an id that is not in flight: the transport consumes the
        // notification and writes nothing back.
        sendNotification("notifications/cancelled", Map.of("requestId", 987654));
        server.expectNoMessage(Duration.ofSeconds(1));
    }

    private void sendNotification(String method, Map<String, Object> params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        server.sendLine(JSON.writeValueAsString(notification));
    }

    @Test
    @DisplayName("audit correlation id equals the JSON-RPC request id")
    void auditCorrelationMatchesRequestId() throws IOException {
        JsonNode response = server.callTool("echo", Map.of("echo", "correlated"));
        String correlation = response.get("id").asText();

        JsonNode line = auditLineFor(correlation);
        assertThat(line.get("correlation").asText()).isEqualTo(correlation);
        assertThat(line.get("identity").asText()).isEqualTo("stdio-client");
        assertThat(line.get("outcome").asText()).isEqualTo("OK");
        assertThat(line.get("durationMs").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("oversized request line is refused with -32600 and the session resyncs")
    void oversizedRequestLine() throws IOException {
        // Separate process: the line cap is startup configuration, not per-request.
        Path smallRoot = Files.createTempDirectory("mcp-protocol-it-small-");
        Path smallAudit = smallRoot.resolve("audit.log");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MCP_ROLE", "VIEWER");
        env.put("MCP_FS_ROOTS", smallRoot.toString());
        env.put("MCP_AUDIT_FILE", smallAudit.toString());
        env.put("MCP_MAX_LINE_CHARS", "500");

        try (McpServerProcess small = McpServerProcess.start(
                Path.of(System.getProperty("server.jar")), env)) {
            small.initialize();

            String huge = "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"echo\",\"arguments\":{\"echo\":\""
                    + "x".repeat(800) + "\"}}}";
            JsonNode error = small.sendRaw(huge);

            assertThat(error.get("error").get("code").asInt()).isEqualTo(-32600);
            assertThat(error.get("error").get("message").asText()).isEqualTo("Request too large");

            assertThat(small.callTool("ping", Map.of()).get("result").get("isError").asBoolean())
                    .isFalse();
            small.assertStdoutPure();
        }
    }

    private static JsonNode toolByName(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("tool not advertised: " + name);
    }

    private JsonNode auditLineFor(String correlation) throws IOException {
        List<JsonNode> lines = McpServerProcess.auditLines(auditFile);
        return lines.stream()
                .filter(line -> correlation.equals(line.get("correlation").asText()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "no audit line for correlation " + correlation + "; lines: "
                                + lines.stream().map(JsonNode::toString)
                                .collect(Collectors.joining(", "))));
    }
}
