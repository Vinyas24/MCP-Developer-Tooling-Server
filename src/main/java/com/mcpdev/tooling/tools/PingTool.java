package com.mcpdev.tooling.tools;

import com.mcpdev.tooling.McpToolingApplication;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class PingTool implements Tool<PingTool.Args> {

    public record Args() {
    }

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public String description() {
        return "Liveness check. Returns pong plus server version.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        return ToolResult.text("pong v" + McpToolingApplication.VERSION);
    }
}
