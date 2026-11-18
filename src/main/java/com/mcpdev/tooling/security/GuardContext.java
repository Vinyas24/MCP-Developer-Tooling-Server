package com.mcpdev.tooling.security;

import com.mcpdev.tooling.registry.Tool;

import java.util.Map;

/**
 * Per-call state threaded through the guard chain. Mutable by design: guards enrich
 * it (identity) as the chain progresses.
 */
public final class GuardContext {

    private final Tool<?> tool;
    private final Map<String, Object> rawArgs;
    private final int rawArgsBytes;
    private final String correlationId;
    private Identity identity;

    public GuardContext(Tool<?> tool, Map<String, Object> rawArgs, int rawArgsBytes,
                        String correlationId) {
        this.tool = tool;
        this.rawArgs = rawArgs;
        this.rawArgsBytes = rawArgsBytes;
        this.correlationId = correlationId;
    }

    public Tool<?> tool() {
        return tool;
    }

    public Map<String, Object> rawArgs() {
        return rawArgs;
    }

    public int rawArgsBytes() {
        return rawArgsBytes;
    }

    public String correlationId() {
        return correlationId;
    }

    public Identity identity() {
        return identity;
    }

    public void identity(Identity identity) {
        this.identity = identity;
    }
}
