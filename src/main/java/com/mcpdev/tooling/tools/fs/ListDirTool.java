package com.mcpdev.tooling.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mcpdev.tooling.config.FileSystemProperties;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolInputException;
import com.mcpdev.tooling.registry.ToolResult;
import com.mcpdev.tooling.security.PathSandbox;
import org.springframework.stereotype.Component;

/**
 * Lists a directory. Never follows symlinks during traversal (cycle-safe): links are
 * shown as {@code name -> target}, target resolved only for display, best-effort.
 */
@Component
public class ListDirTool implements Tool<ListDirTool.Args> {

    public record Args(String path, Optional<Integer> depth) {
    }

    private static final DateTimeFormatter MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final PathSandbox sandbox;
    private final FileSystemProperties props;

    public ListDirTool(PathSandbox sandbox, FileSystemProperties props) {
        this.sandbox = sandbox;
        this.props = props;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "Lists a directory inside an allowed root. Directories end with '/', "
                + "symlinks show 'name -> target'. .git, node_modules and similar are hidden.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        PathSandbox.Resolved resolved = sandbox.resolve(args.path());
        int depth = args.depth().orElse(1);
        if (depth < 1 || depth > props.maxListDepth()) {
            throw new ToolInputException("depth must be between 1 and " + props.maxListDepth());
        }

        Path start = resolved.real();
        if (!Files.isDirectory(start)) {
            throw new ToolExecutionException("not a directory: " + args.path());
        }

        var out = new StringBuilder();
        int[] count = {0};
        boolean[] truncated = {false};

        walk(start, resolved.display(), depth, 0, out, count, truncated);

        if (truncated[0]) {
            out.append("… truncated at ").append(props.maxListEntries()).append(" entries\n");
        }
        return ToolResult.text(out.toString());
    }

    private void walk(Path dir, Path display, int maxDepth, int level,
                      StringBuilder out, int[] count, boolean[] truncated) {
        if (truncated[0] || level >= maxDepth) {
            return;
        }
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            stream.forEach(children::add);
        } catch (IOException e) {
            out.append("(cannot read ").append(relativize(display, dir)).append(")\n");
            return;
        }
        children.sort((a, b) -> {
            boolean da = Files.isDirectory(a);
            boolean db = Files.isDirectory(b);
            int byType = Boolean.compare(db, da); // directories first
            return byType != 0 ? byType : a.getFileName().toString()
                    .compareToIgnoreCase(b.getFileName().toString());
        });

        for (Path child : children) {
            String name = child.getFileName() == null ? "?" : child.getFileName().toString();
            if (props.ignore().contains(name)) {
                continue;
            }
            if (count[0] >= props.maxListEntries()) {
                truncated[0] = true;
                return;
            }
            count[0]++;
            render(child, relativize(display, child), out);
            try {
                BasicFileAttributes attrs = Files.readAttributes(child,
                        BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (attrs.isDirectory() && !attrs.isSymbolicLink()) {
                    walk(child, display, maxDepth, level + 1, out, count, truncated);
                }
            } catch (IOException e) {
                // already rendered; nothing to descend into
            }
        }
    }

    private void render(Path child, Path displayPath, StringBuilder out) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
                String target = safeLinkTarget(child);
                out.append(displayPath).append(target.isEmpty() ? "" : " -> " + target).append('\n');
            } else if (attrs.isDirectory()) {
                out.append(displayPath).append("/\n");
            } else {
                out.append(displayPath).append("  (").append(attrs.size()).append(" bytes, modified ")
                        .append(MODIFIED.format(attrs.lastModifiedTime().toInstant()))
                        .append(")\n");
            }
        } catch (IOException e) {
            out.append(displayPath).append("  (inaccessible)\n");
        }
    }

    private String safeLinkTarget(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static Path relativize(Path display, Path child) {
        try {
            return display.relativize(child);
        } catch (IllegalArgumentException e) {
            return child.getFileName() == null ? child : child.getFileName();
        }
    }
}
