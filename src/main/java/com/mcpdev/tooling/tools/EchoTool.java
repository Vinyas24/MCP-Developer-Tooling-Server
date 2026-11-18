package com.mcpdev.tooling.tools;

import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolInputException;
import com.mcpdev.tooling.registry.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Exercises the required-vs-optional argument paths of the framework. */
@Component
public class EchoTool implements Tool<EchoTool.Args> {

    private static final int MAX_REPEAT = 50;

    public record Args(String echo, Optional<Integer> repeat) {
    }

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public String description() {
        return "Echoes the given text back, optionally repeated.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        int repeat = args.repeat().orElse(1);
        if (repeat < 1 || repeat > MAX_REPEAT) {
            throw new ToolInputException("repeat must be between 1 and " + MAX_REPEAT);
        }
        return ToolResult.text((args.echo() + "\n").repeat(repeat).trim());
    }
}
