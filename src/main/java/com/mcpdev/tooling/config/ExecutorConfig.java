package com.mcpdev.tooling.config;

import com.mcpdev.tooling.executor.ToolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "close")
    ToolExecutor toolExecutor(ExecutorProperties properties) {
        return new ToolExecutor(
                properties.maxConcurrentCalls(),
                properties.queueWaitMillis(),
                properties.callTimeoutMillis(),
                properties.statsIntervalSeconds());
    }
}
