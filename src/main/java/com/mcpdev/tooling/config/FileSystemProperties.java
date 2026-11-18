package com.mcpdev.tooling.config;

import java.util.List;

import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem tooling configuration. Roots are absolute directory paths; env var
 * {@code MCP_FS_ROOTS=/a,/b} binds via relaxed binding. No roots configured means
 * every FS tool call fails closed — deny by default.
 */
@ConfigurationProperties(prefix = "mcp.fs")
public record FileSystemProperties(
        List<String> roots,
        @DefaultValue("1048576") long maxFileBytes,
        @DefaultValue("2000") int maxLines,
        @DefaultValue("500") int maxListEntries,
        @DefaultValue("3") int maxListDepth,
        @DefaultValue("100") int maxSearchMatches,
        @DefaultValue("2000") int maxSearchFiles,
        @DefaultValue("524288") int maxSearchFileBytes,
        @DefaultValue("1000") int maxSearchLineLength,
        @DefaultValue("5000") long searchDeadlineMillis,
        @DefaultValue("512") int maxQueryLength,
        @DefaultValue(".git,node_modules,target,build,dist,out,.idea,.vscode,__pycache__,.venv,venv,bin,obj")
        List<String> ignore) {

    public static final List<String> DEFAULT_IGNORE = List.of(
            ".git", "node_modules", "target", "build", "dist", "out", ".idea", ".vscode",
            "__pycache__", ".venv", "venv", "bin", "obj");

    /** Test/programmatic factory applying production defaults. */
    public static FileSystemProperties withDefaults(List<String> roots) {
        return new FileSystemProperties(roots, 1_048_576, 2000, 500, 3, 100, 2000,
                524_288, 1000, 5000, 512, DEFAULT_IGNORE);
    }

    public FileSystemProperties {
        roots = roots == null ? List.of() : roots.stream().filter(r -> r != null && !r.isBlank()).toList();
        // Boot 4.1 binds @DefaultValue for collections as ONE comma-joined element,
        // not split — normalize here so bind-time and programmatic paths agree.
        if (ignore != null && ignore.size() == 1 && ignore.get(0).contains(",")) {
            ignore = java.util.Arrays.stream(ignore.get(0).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } else {
            ignore = ignore == null ? List.of() : List.copyOf(ignore);
        }
    }
}
