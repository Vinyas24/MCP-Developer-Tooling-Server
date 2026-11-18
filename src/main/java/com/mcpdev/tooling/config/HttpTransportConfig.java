package com.mcpdev.tooling.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mcpdev.tooling.transport.McpErrorHttpMessageConverter;

/**
 * HTTP-profile wiring: a startup notice and the error-frame converter.
 *
 * <p>The transport itself is Spring AI's Streamable-HTTP provider. What matters here
 * is that the operator is told, loudly, what they just exposed, and that protocol
 * errors are rendered as JSON-RPC errors rather than serialized exceptions.
 *
 * <p>Deliberately no authentication: a half-baked token check would imply a security
 * boundary this server does not have. The honest boundary is loopback binding plus
 * the process-wide role.
 */
@Configuration
@Profile("http")
public class HttpTransportConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(HttpTransportConfig.class);

    @Bean
    ApplicationListener<WebServerInitializedEvent> httpTransportNotice() {
        return event -> log.warn("MCP Streamable HTTP listening on "
                + "http://127.0.0.1:{}/mcp — UNAUTHENTICATED dev transport; identity is "
                + "the process-wide MCP_ROLE. Do not expose this port.",
                event.getWebServer().getPort());
    }

    /**
     * Ahead of Jackson so {@code McpError} bodies become JSON-RPC error frames instead
     * of serialized stack traces. Only this one type is claimed.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new McpErrorHttpMessageConverter());
    }
}
