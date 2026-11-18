package com.mcpdev.tooling.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;

/**
 * Turns the SDK's HTTP error path into a spec-shaped JSON-RPC error frame.
 *
 * <p><b>Why this exists.</b> {@code WebMvcStreamableServerTransportProvider} answers
 * an unparseable POST with {@code ServerResponse.badRequest().body(McpError)}. Spring
 * MVC then serializes the <em>exception object</em>, so the client receives
 * {@code {"jsonRpcError":…,"cause":…,"stackTrace":[{"className":"…"}]}} — internal
 * class names, file names and line numbers. That breaks the project's "no stack
 * traces to clients" invariant, and it is not something application code can catch:
 * the transport builds the response itself.
 *
 * <p>A converter registered ahead of Jackson's handles the body cleanly, so clients
 * get {@code {"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"…"}}} —
 * the same shape the hardened stdio transport writes for parse errors.
 */
public final class McpErrorHttpMessageConverter implements HttpMessageConverter<McpError> {

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return false; // server-side write-only
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return clazz != null && McpError.class.isAssignableFrom(clazz);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return List.of(MediaType.APPLICATION_JSON, MediaType.ALL);
    }

    @Override
    public McpError read(Class<? extends McpError> clazz, HttpInputMessage inputMessage) {
        throw new UnsupportedOperationException("write-only converter");
    }

    @Override
    public void write(McpError error, MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException {
        McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError = error.getJsonRpcError();
        int code = jsonRpcError == null || jsonRpcError.code() == null
                ? -32603 : jsonRpcError.code();
        String message = jsonRpcError == null || jsonRpcError.message() == null
                ? "Internal error" : jsonRpcError.message();

        String frame = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":" + code
                + ",\"message\":\"" + escape(message) + "\"}}";
        outputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        outputMessage.getBody().write(frame.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
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
}
