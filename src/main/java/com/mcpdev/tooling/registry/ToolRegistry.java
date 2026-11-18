package com.mcpdev.tooling.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Authoritative name -> tool map. Deny-by-default lookup for the guard chain (Phase 5):
 * an unknown name throws rather than resolving to anything.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool<?>> tools = new LinkedHashMap<>();

    public void register(Tool<?> tool) {
        String name = tool.name();
        Tool<?> existing = tools.putIfAbsent(name, tool);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate tool name '" + name + "': "
                            + existing.getClass().getName() + " vs "
                            + tool.getClass().getName());
        }
    }

    public Tool<?> byName(String name) {
        Tool<?> tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool;
    }

    public Collection<Tool<?>> all() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
