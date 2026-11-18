package com.mcpdev.tooling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dedicated mapper for tool argument binding. Strict on purpose:
 * unknown properties are rejected (client drift / smuggling defense), missing
 * record properties fail (required-by-convention). Deliberately NOT the Boot
 * autoconfigured mapper — framework behavior must not track app-wide Jackson settings.
 */
@Configuration
public class ToolJsonConfig {

    @Bean(name = "toolJsonMapper")
    public JsonMapper toolJsonMapper() {
        return JsonMapper.builder()
                // Unknown properties are rejected (client drift / smuggling defense).
                // Requiredness is enforced by ToolArgsBinder, not by a Jackson feature,
                // for precise messages and independence from mapper defaults.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
