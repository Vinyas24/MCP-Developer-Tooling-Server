package com.mcpdev.tooling.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolInputException;

/**
 * The single authority for "may this tool touch this path". Two containment layers:
 *
 * <ol>
 *   <li><b>Lexical</b> (no filesystem access): the normalized path must sit under a
 *       configured root. Catches {@code ..} traversal cheaply and deterministically.</li>
 *   <li><b>Real</b> (follows symlinks): the fully resolved target must sit under a
 *       root's real path. Catches symlink escapes, including links that point out of
 *       the tree and links that re-enter it from elsewhere.</li>
 * </ol>
 *
 * <p>Known residual risk: TOCTOU between resolution and open (a local process swapping
 * a symlink mid-call). Out of threat model for a local dev tool — the adversary is the
 * LLM client, not a co-resident process racing us. Documented, not mitigated.</p>
 */
public final class PathSandbox {

    public record Root(String configured, Path lexical, Path real) {
    }

    public record Resolved(Root root, Path display, Path real) {
    }

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    /** Most specific root first, so containment checks report the tightest container. */
    private final List<Root> roots;

    public PathSandbox(List<String> configuredRoots) {
        if (configuredRoots == null || configuredRoots.isEmpty()) {
            this.roots = List.of();
            return;
        }
        this.roots = configuredRoots.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(entry -> {
                    try {
                        Path p = Path.of(entry);
                        if (!p.isAbsolute()) {
                            throw new IllegalStateException("FS root must be absolute: " + entry);
                        }
                        Path real = p.toRealPath(); // follows links; root itself may be a symlink
                        if (!Files.isDirectory(real, NOFOLLOW)) {
                            throw new IllegalStateException("FS root is not a directory: " + entry);
                        }
                        return new Root(entry, p.normalize(), real);
                    } catch (IOException e) {
                        throw new IllegalStateException("FS root cannot be resolved: " + entry, e);
                    }
                })
                .sorted(Comparator.comparingInt((Root r) -> r.real().getNameCount()).reversed())
                .toList();
    }

    /** False when no roots are configured — tools must fail closed. */
    public boolean enabled() {
        return !roots.isEmpty();
    }

    public List<Root> roots() {
        return roots;
    }

    /** Message shared by every FS tool when no roots are configured. */
    public static final String DISABLED_MESSAGE =
            "Filesystem tools are disabled: no allowed roots configured (set MCP_FS_ROOTS)";

    public Resolved resolve(String raw) {
        if (!enabled()) {
            throw new ToolExecutionException(DISABLED_MESSAGE);
        }
        if (raw == null || raw.isBlank()) {
            throw new ToolInputException("path is required");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new ToolInputException("path must not contain null bytes");
        }

        Path p = Path.of(raw);
        if (!p.isAbsolute()) {
            if (roots.size() == 1) {
                p = roots.get(0).lexical().resolve(p);
            } else {
                throw new ToolInputException(
                        "relative path given but " + roots.size()
                                + " roots are configured — pass an absolute path");
            }
        }

        Path lexical = p.normalize();

        // Layer 1: lexical containment (accept either the configured or the real form
        // of a root, so symlinked roots don't cause false rejections).
        Root lexicalRoot = roots.stream()
                .filter(r -> lexical.startsWith(r.lexical()) || lexical.startsWith(r.real()))
                .findFirst()
                .orElseThrow(() -> new ToolExecutionException(
                        "path is outside the allowed filesystem roots"));

        // Layer 2: existence + symlink resolution, then re-check on the real target.
        Path real;
        try {
            real = lexical.toRealPath();
        } catch (IOException e) {
            if (e instanceof java.nio.file.NoSuchFileException) {
                throw new ToolExecutionException("file not found: " + lexical);
            }
            throw new ToolExecutionException("path is not accessible: "
                    + e.getClass().getSimpleName());
        }
        Root realRoot = roots.stream()
                .filter(r -> real.startsWith(r.real()))
                .findFirst()
                .orElseThrow(() -> new ToolExecutionException(
                        "path escapes the allowed filesystem roots through symbolic links"));

        return new Resolved(realRoot, lexical, real);
    }
}
