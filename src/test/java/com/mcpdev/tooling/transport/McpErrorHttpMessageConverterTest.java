package com.mcpdev.tooling.transport;

import io.modelcontextprotocol.spec.McpError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpOutputMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP profile must not serialize SDK exceptions: a client that sends garbage
 * gets a JSON-RPC error frame, never class names or stack frames.
 */
class McpErrorHttpMessageConverterTest {

    private final McpErrorHttpMessageConverter converter = new McpErrorHttpMessageConverter();

    @Test
    @DisplayName("McpError becomes a spec-shaped JSON-RPC error frame, no internals")
    void rendersCleanFrame() throws Exception {
        McpError error = McpError.builder(-32600).message("Invalid message format").build();
        MockHttpOutputMessage output = new MockHttpOutputMessage();

        converter.write(error, MediaType.APPLICATION_JSON, output);

        String body = output.getBodyAsString();
        assertThat(body)
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":null,"
                        + "\"error\":{\"code\":-32600,\"message\":\"Invalid message format\"}}");
        assertThat(body)
                .doesNotContain("stackTrace")
                .doesNotContain("className")
                .doesNotContain("jsonRpcError");
        assertThat(output.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("messages are escaped, so a quote cannot break the frame")
    void escapesMessage() throws Exception {
        McpError error = McpError.builder(-32700)
                .message("bad \"quote\" and \\ backslash\nnewline")
                .build();
        MockHttpOutputMessage output = new MockHttpOutputMessage();

        converter.write(error, MediaType.APPLICATION_JSON, output);

        assertThat(output.getBodyAsString())
                .contains("\\\"quote\\\"")
                .contains("\\\\ backslash")
                .contains("\\nnewline");
    }

    @Test
    @DisplayName("claims only McpError, so JSON for every other body type is untouched")
    void claimsOnlyMcpError() {
        assertThat(converter.canWrite(McpError.class, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(converter.canWrite(String.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canWrite(Object.class, MediaType.APPLICATION_JSON)).isFalse();
    }
}
