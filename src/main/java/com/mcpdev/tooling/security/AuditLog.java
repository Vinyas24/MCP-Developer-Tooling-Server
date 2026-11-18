package com.mcpdev.tooling.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only audit trail, one JSON object per line. Fail-closed: a failed audit
 * write DENIES the call — an audit log you can lose silently is decoration.
 *
 * <p>Records contain no argument values — only a SHA-256 prefix of the canonical
 * args JSON — so secrets/search terms never land in the file.</p>
 *
 * <p>ReentrantLock (not synchronized): Phase 6 runs tools on virtual threads and
 * {@code synchronized} blocks pin carriers; audit writes are hot under load.</p>
 */
public class AuditLog {

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();

    public AuditLog(Path file) {
        this.file = file;
        try {
            Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("audit log cannot be created: " + file, e);
        }
    }

    public enum Outcome {
        OK, DENIED_IDENTITY, DENIED_PERMISSION, DENIED_RATE, DENIED_SIZE,
        DENIED_DOMAIN, DENIED_BACKPRESSURE, INVALID_ARGS, TOOL_ERROR,
        TIMEOUT, CANCELLED, INTERNAL_ERROR
    }

    /** Write one audit line. Throws on any IO failure — callers must deny the request. */
    public void write(String correlationId, Identity identity, String toolName,
                      String argsHash, Outcome outcome, long durationMs) {
        String line = "{\"ts\":\"" + java.time.Instant.now() + "\""
                + ",\"correlation\":\"" + jsonEscape(correlationId) + "\""
                + ",\"identity\":\"" + jsonEscape(identity.name()) + "\""
                + ",\"role\":\"" + (identity.role() == null ? "UNKNOWN" : identity.role()) + "\""
                + ",\"tool\":\"" + jsonEscape(toolName) + "\""
                + ",\"argsSha256\":\"" + jsonEscape(argsHash) + "\""
                + ",\"outcome\":\"" + outcome + "\""
                + ",\"durationMs\":" + durationMs + "}\n";

        lock.lock();
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("audit log write failed — call denied", e);
        } finally {
            lock.unlock();
        }
    }

    public Path file() {
        return file;
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        var out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
