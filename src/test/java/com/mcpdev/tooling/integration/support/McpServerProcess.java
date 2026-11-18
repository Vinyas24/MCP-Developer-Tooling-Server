package com.mcpdev.tooling.integration.support;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.fail;

/**
 * Black-box driver for the packaged server over stdio.
 *
 * <p>Every stdout line is parsed. A line that is not JSON fails the test immediately:
 * stdout purity (no log lines, no banner) is part of the transport contract, and a
 * harness that only matched ids would silently tolerate it.
 */
public final class McpServerProcess implements AutoCloseable {

    private static final String EOF = "\u0000EOF";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Process process;
    private final Path stderrFile;
    private final BufferedWriter stdin;
    private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
    private final List<String> nonJsonLines = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestIds = new AtomicInteger(100);

    private McpServerProcess(Process process, Path stderrFile) throws IOException {
        this.process = process;
        this.stderrFile = stderrFile;
        this.stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread pump = new Thread(this::pumpStdout, "it-stdout-pump");
        pump.setDaemon(true);
        pump.start();
    }

    public static McpServerProcess start(Path jar, Map<String, String> environment,
                                         String... applicationArgs) throws IOException {
        if (!Files.isRegularFile(jar)) {
            fail("server jar not built: %s — run `mvn verify` (the jar is built before ITs)",
                    jar.toAbsolutePath());
        }
        Path stderrFile = Files.createTempFile("mcp-it-stderr-", ".log");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toAbsolutePath().toString()));
        command.addAll(List.of(applicationArgs));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        builder.redirectError(stderrFile.toFile());
        Process process = builder.start();
        return new McpServerProcess(process, stderrFile);
    }

    private void pumpStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdout.offer(line);
            }
        } catch (IOException e) {
            // process died; EOF below lets the waiting test fail with context
        } finally {
            stdout.offer(EOF);
        }
    }

    /** Handshake, then the initialized notification. Returns the initialize result. */
    public JsonNode initialize() {
        JsonNode result = call("initialize", Map.of(
                "protocolVersion", "2025-11-25",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "mcp-it", "version", "0")));
        notify("notifications/initialized");
        return result;
    }

    public JsonNode call(String method, Object params) {
        int id = requestIds.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        writeJson(request);
        return awaitResponse(String.valueOf(id), Duration.ofSeconds(20));
    }

    public JsonNode callTool(String name, Map<String, Object> arguments) {
        return call("tools/call", Map.of("name", name, "arguments", arguments));
    }

    /** Sends a raw line (malformed JSON, batch array, oversized payload). */
    public JsonNode sendRaw(String raw) {
        writeLine(raw);
        return nextMessage(Duration.ofSeconds(20));
    }

    /** Sends a raw line without waiting — for notifications, which must stay silent. */
    public void sendLine(String raw) {
        writeLine(raw);
    }

    public void notify(String method) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        writeJson(notification);
    }

    /** Asserts the server writes nothing (notifications and cancelled calls stay silent). */
    public void expectNoMessage(Duration window) {
        try {
            String line = stdout.poll(window.toMillis(), TimeUnit.MILLISECONDS);
            if (line != null && !EOF.equals(line)) {
                fail("expected no response, got: %s", line);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Next message of any shape — used to prove notifications produce no response. */
    public JsonNode nextMessage(Duration timeout) {
        String line = poll(timeout);
        return parse(line);
    }

    public int awaitExit(Duration timeout) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return fail("server did not exit within %s; stderr:\n%s", timeout, stderr());
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public String stderr() {
        try {
            return Files.readString(stderrFile);
        } catch (IOException e) {
            return "<stderr unavailable: " + e + ">";
        }
    }

    public void assertStdoutPure() {
        if (!nonJsonLines.isEmpty()) {
            fail("stdout carried non-JSON output (logging must go to stderr): %s", nonJsonLines);
        }
    }

    private JsonNode awaitResponse(String expectedId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return fail("no response for id %s within %s; stderr:\n%s",
                        expectedId, timeout, stderr());
            }
            String line = poll(Duration.ofNanos(remaining));
            if (line.equals(EOF)) {
                return fail("server closed stdout while waiting for id %s; stderr:\n%s",
                        expectedId, stderr());
            }
            JsonNode message = parse(line);
            if (message.has("id")
                    && expectedId.equals(message.get("id").asText())) {
                return message;
            }
            // out-of-order or notification: keep waiting, the test asserts what it needs
        }
    }

    private JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (RuntimeException e) {
            nonJsonLines.add(line);
            return fail("non-JSON line on stdout: %s", line);
        }
    }

    private String poll(Duration timeout) {
        try {
            String line = stdout.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) {
                return fail("stdout silent for %s; stderr:\n%s", timeout, stderr());
            }
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void writeJson(Object message) {
        writeLine(JSON.writeValueAsString(message));
    }

    private synchronized void writeLine(String line) {
        try {
            stdin.write(line);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            throw new IllegalStateException("failed writing to server stdin; stderr:\n" + stderr(), e);
        }
    }

    @Override
    public void close() {
        try {
            stdin.close(); // EOF: the server's read loop ends and it shuts down
        } catch (IOException ignored) {
            // process is going away regardless
        }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Parsed audit lines, in file order. Empty when the file is absent. */
    public static List<JsonNode> auditLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                lines.add(JSON.readTree(line));
            }
        }
        return lines;
    }
}
