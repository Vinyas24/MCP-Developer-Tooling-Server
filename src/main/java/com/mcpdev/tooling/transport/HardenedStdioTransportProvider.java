package com.mcpdev.tooling.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import com.mcpdev.tooling.protocol.ProtocolKeys;

/**
 * Stdio transport with the three hardening fixes the SDK 2.1.0 implementation lacks:
 *
 * <ol>
 *   <li><b>Cancellation</b> — {@code notifications/cancelled} is intercepted, mapped
 *       to the in-flight call by JSON-RPC id, and forwarded to the executor. The SDK
 *       has no handler for it at all, so without this a cancel is silently dropped.</li>
 *   <li><b>Malformed input</b> — an unparseable line yields a JSON-RPC {@code -32700}
 *       and the read loop CONTINUES. In the stock transport it kills the reader thread
 *       and silently ends the session.</li>
 *   <li><b>Bounded reads</b> — lines are capped before allocation, so a hostile peer
 *       cannot force an unbounded buffer.</li>
 * </ol>
 *
 * <p>The request id is injected into the Reactor context ({@code MCP_TRANSPORT_CONTEXT})
 * so the tool handler can register itself for cancellation — the SDK never exposes the
 * JSON-RPC id to a call handler otherwise.
 *
 * <p>Writes are serialized on one lock: responses produced by concurrent virtual
 * threads must not interleave bytes on stdout.
 */
public class HardenedStdioTransportProvider implements McpServerTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(HardenedStdioTransportProvider.class);
    private static final String CANCELLED = "notifications/cancelled";
    private static final String REQUEST_ID_KEY = ProtocolKeys.REQUEST_ID;

    private final McpJsonMapper jsonMapper;
    private final InputStream input;
    private final OutputStream output;
    private final int maxLineChars;
    private final Consumer<Object> cancellationSink;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(
            // PLATFORM, non-daemon: this thread is what keeps the JVM alive for the
            // life of the stdin stream. Virtual threads are always daemon, so using
            // one here makes the process exit as soon as main() returns.
            Thread.ofPlatform().name("mcp-stdio-reader").factory());
    private volatile McpServerSession session;

    public HardenedStdioTransportProvider(McpJsonMapper jsonMapper, InputStream input,
                                          OutputStream output, int maxLineChars,
                                          Consumer<Object> cancellationSink) {
        this.jsonMapper = jsonMapper;
        this.input = input;
        this.output = output;
        this.maxLineChars = maxLineChars;
        this.cancellationSink = cancellationSink;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory factory) {
        this.session = factory.create(new StdioSessionTransport());
        reader.execute(this::readLoop);
    }

    private final class StdioSessionTransport implements McpServerTransport {

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    writeLine(jsonMapper.writeValueAsString(message));
                } catch (IOException e) {
                    log.error("failed to serialize outbound message", e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                closed.set(true);
                reader.shutdown();
            });
        }

        @Override
        public void close() {
            closed.set(true);
            reader.shutdownNow();
        }
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while (!closed.get() && (line = readBoundedLine(in)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    dispatch(line);
                } catch (RuntimeException e) {
                    // one malformed/unsupported message must never kill the session
                    log.error("dispatch failed for line ({} chars)", line.length(), e);
                    writeError(null, -32603, "Internal error");
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                log.error("stdio read failed", e);
            }
        } catch (RuntimeException e) {
            log.error("stdio reader died unexpectedly", e);
        } finally {
            closeSession();
        }
    }

    private void dispatch(String line) {
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
        } catch (Exception e) {
            // One bad line must not kill the session: answer, resync, keep reading.
            log.warn("malformed JSON-RPC line ({} chars): {}", line.length(), e.getMessage());
            writeError(null, -32700, "Parse error");
            return;
        }

        if (message instanceof McpSchema.JSONRPCNotification notification
                && CANCELLED.equals(notification.method())) {
            Object requestId = requestIdOf(notification.params());
            if (requestId != null) {
                log.info("cancel requested for request id {}", requestId);
                cancellationSink.accept(requestId);
            }
            return; // the SDK has no handler for this notification by design
        }

        Object requestId = message instanceof McpSchema.JSONRPCRequest request ? request.id() : null;
        McpServerSession current = session;
        if (current == null) {
            writeError(requestId, -32603, "Server not initialized");
            return;
        }

        current.handle(message)
                .contextWrite(context -> {
                    if (requestId == null) {
                        return context;
                    }
                    return context.put(McpTransportContext.KEY,
                            McpTransportContext.create(Map.of(REQUEST_ID_KEY, requestId)));
                })
                .subscribe(null, error -> {
                    log.error("dispatch failed for {}", message, error);
                    if (requestId != null) {
                        writeError(requestId, -32603, "Internal error");
                    }
                });
    }

    private static Object requestIdOf(Object params) {
        if (params instanceof Map<?, ?> map) {
            return map.get("requestId");
        }
        return null;
    }

    private void writeLine(String json) {
        byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            try {
                output.write(bytes);
                output.flush();
            } catch (IOException e) {
                log.error("stdio write failed", e);
            }
        }
    }

    /**
     * JSON-RPC error frame written by hand: the SDK's {@code JSONRPCResponse} record
     * rejects a null id, but the spec REQUIRES {@code id:null} for parse errors — we
     * never saw a valid id, so there is nothing else to echo.
     */
    private void writeError(Object id, int code, String message) {
        String idJson = id == null ? "null"
                : id instanceof Number ? id.toString()
                : "\"" + jsonEscape(String.valueOf(id)) + "\"";
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + jsonEscape(message) + "\"}}";
        writeLine(frame);
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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

    /** Bounded line reader: refuses to buffer more than {@code maxLineChars}. */
    private String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean overflow = false;
        int c;
        boolean sawAny = false;
        while ((c = reader.read()) != -1) {
            sawAny = true;
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            if (line.length() < maxLineChars) {
                line.append((char) c);
            } else {
                overflow = true; // keep draining to the newline to resync
            }
        }
        if (c == -1 && !sawAny) {
            return null;
        }
        if (overflow) {
            log.warn("dropping over-long JSON-RPC line (> {} chars)", maxLineChars);
            writeError(null, -32600, "Request too large");
            return "";
        }
        return line.toString();
    }

    private void closeSession() {
        McpServerSession current = session;
        if (current != null) {
            try {
                current.closeGracefully().block(java.time.Duration.ofSeconds(2));
            } catch (RuntimeException e) {
                log.debug("session close on stdin EOF failed", e);
            }
        }
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        McpServerSession current = session;
        return current == null ? Mono.empty() : current.sendNotification(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return notifyClients(method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closed.set(true);
            reader.shutdown();
            closeSession();
        });
    }

    @Override
    public void close() {
        closed.set(true);
        reader.shutdownNow();
        try {
            reader.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
