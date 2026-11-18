package com.mcpdev.tooling.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("writes one JSON line per call with all required fields")
    void writesFields() throws IOException {
        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        audit.write("ab12cd34", Identity.of("stdio-client", Role.DEVELOPER),
                "run_query", "0123abcd", AuditLog.Outcome.DENIED_PERMISSION, 42);

        String line = Files.readString(dir.resolve("audit.log")).strip();
        assertThat(line)
                .contains("\"correlation\":\"ab12cd34\"")
                .contains("\"identity\":\"stdio-client\"")
                .contains("\"role\":\"DEVELOPER\"")
                .contains("\"tool\":\"run_query\"")
                .contains("\"argsSha256\":\"0123abcd\"")
                .contains("\"outcome\":\"DENIED_PERMISSION\"")
                .contains("\"durationMs\":42")
                .startsWith("{\"ts\":\"");
    }

    @Test
    @DisplayName("appends: two calls -> two lines")
    void appends() throws IOException {
        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        audit.write("c1", Identity.of("t", Role.VIEWER), "ping", "h1", AuditLog.Outcome.OK, 1);
        audit.write("c2", Identity.of("t", Role.VIEWER), "ping", "h2", AuditLog.Outcome.OK, 2);

        List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"correlation\":\"c1\"");
        assertThat(lines.get(1)).contains("\"correlation\":\"c2\"");
    }

    @Test
    @DisplayName("fail-closed: write failure throws (call must be denied)")
    void failClosed() throws IOException {
        Path file = dir.resolve("audit.log");
        AuditLog audit = new AuditLog(file);
        Files.delete(file);                    // sabotage AFTER successful construction
        Files.createDirectory(file);           // same path is now a directory

        assertThatThrownBy(() -> audit.write("c", Identity.of("t", Role.VIEWER),
                "ping", "h", AuditLog.Outcome.OK, 0))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit log write failed");
    }

    @Test
    @DisplayName("null role (identity failure) serializes as UNKNOWN, never NPE")
    void nullRole() throws IOException {
        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        audit.write("c", new Identity("unknown", null), "ping", "h",
                AuditLog.Outcome.DENIED_IDENTITY, 0);
        assertThat(Files.readString(dir.resolve("audit.log"))).contains("\"role\":\"UNKNOWN\"");
    }

    @Test
    @DisplayName("quotes, backslashes and control characters are escaped, never raw")
    void escapesHostileFields() throws IOException {
        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        audit.write("id\"q\\b\nl\rret\ttab\u0001", Identity.of("t\tab", Role.VIEWER),
                "tool\"name", "h", AuditLog.Outcome.OK, 0);

        List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
        assertThat(lines).as("an embedded newline must not create a second record").hasSize(1);
        assertThat(lines.get(0))
                .contains("\"correlation\":\"id\\\"q\\\\b\\nl\\rret\\ttab\\u0001\"")
                .contains("\"identity\":\"t\\tab\"")
                .contains("\"tool\":\"tool\\\"name\"");
    }

    @Test
    @DisplayName("a null tool name serializes as empty, not the string 'null'")
    void nullToolName() throws IOException {
        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        audit.write("c", Identity.of("t", Role.VIEWER), null, "h",
                AuditLog.Outcome.OK, 0);

        assertThat(Files.readString(dir.resolve("audit.log"))).contains("\"tool\":\"\"");
    }

    @Test
    @DisplayName("concurrent writes stay line-atomic under contention")
    void concurrentWrites() throws Exception {
        AuditLog audit = new AuditLog(dir.resolve("audit.log"));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 200; i++) {
                final int n = i;
                pool.submit(() -> audit.write("c" + n, Identity.of("t", Role.VIEWER),
                        "ping", "h", AuditLog.Outcome.OK, n));
            }
        }
        List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
        assertThat(lines).hasSize(200).allMatch(l -> l.startsWith("{\"ts\":\"") && l.endsWith("}"));
    }
}
