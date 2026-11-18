package com.mcpdev.tooling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class McpToolingApplication {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        SpringApplication.run(McpToolingApplication.class, args);
    }
}
