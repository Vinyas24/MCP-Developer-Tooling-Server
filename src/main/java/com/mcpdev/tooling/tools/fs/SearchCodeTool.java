package com.mcpdev.tooling.tools.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.mcpdev.tooling.config.FileSystemProperties;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolInputException;
import com.mcpdev.tooling.registry.ToolResult;
import com.mcpdev.tooling.security.PathSandbox;
import org.springframework.stereotype.Component;

/**
 * Literal/regex text search over allowed roots. Walk is pruned by the ignore list,
 * never follows symlinks, skips binary and oversized files, and stops at the match
 * cap, file cap, or wall-clock deadline — whichever comes first.
 *
 * <p>Residual risk: catastrophic regex backtracking can still burn CPU inside a single
 * {@code find()} on a long line (we cap line length and pattern length, but Java regex
 * is not interruptible). The default is literal matching; Phase 6 adds executor-level
 * timeouts as the hard backstop.</p>
 */
@Component
public class SearchCodeTool implements Tool<SearchCodeTool.Args> {

    public record Args(String query, Optional<String> path, Optional<String> glob,
                       Optional<Boolean> isRegex, Optional<Boolean> caseSensitive) {
    }

    private static final int MAX_PATTERN_LENGTH = 200;
    private static final int MAX_SNIPPET_LENGTH = 240;

    private final PathSandbox sandbox;
    private final FileSystemProperties props;

    public SearchCodeTool(PathSandbox sandbox, FileSystemProperties props) {
        this.sandbox = sandbox;
        this.props = props;
    }

    @Override
    public String name() {
        return "search_code";
    }

    @Override
    public String description() {
        return "Searches file contents under an allowed root. Literal match by default; "
                + "set isRegex=true for regular expressions. Returns file:line: excerpt lines.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        String query = args.query() == null ? "" : args.query().trim();
        if (query.isEmpty()) {
            throw new ToolInputException("query is required");
        }
        if (query.length() > props.maxQueryLength()) {
            throw new ToolInputException("query exceeds " + props.maxQueryLength() + " characters");
        }

        PathSandbox.Resolved scope = args.path().isPresent()
                ? sandbox.resolve(args.path().get())
                : singleRootScope();
        Path start = scope.real();

        boolean regex = args.isRegex().orElse(false);
        boolean caseSensitive = args.caseSensitive().orElse(false);
        Pattern pattern = compile(query, regex, caseSensitive);
        PathMatcher glob = args.glob()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g))
                .orElse(null);

        var out = new StringBuilder();
        Stats stats = new Stats();
        long deadline = System.nanoTime() + props.searchDeadlineMillis() * 1_000_000L;
        Path displayBase = Files.isDirectory(start) ? start : start.getParent();

        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(start) && props.ignore().contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (System.nanoTime() > deadline) {
                        stats.deadlineHit = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (System.nanoTime() > deadline) {
                        stats.deadlineHit = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (stats.filesVisited >= props.maxSearchFiles()) {
                        stats.fileCapHit = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (glob != null && !glob.matches(file)
                            && (file.getFileName() == null || !glob.matches(file.getFileName()))) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > props.maxSearchFileBytes()) {
                        stats.bigSkipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    stats.filesVisited++;
                    try {
                        if (FsSupport.looksBinary(file)) {
                            stats.binarySkipped++;
                            return FileVisitResult.CONTINUE;
                        }
                        scan(file, displayBase, pattern, out, stats);
                    } catch (IOException e) {
                        stats.unreadable++;
                    }
                    return stats.matchCapHit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    stats.unreadable++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolExecutionException("search failed: " + e.getClass().getSimpleName());
        }

        appendSummary(out, stats);
        return ToolResult.text(out.toString());
    }

    private PathSandbox.Resolved singleRootScope() {
        if (!sandbox.enabled()) {
            throw new ToolExecutionException(PathSandbox.DISABLED_MESSAGE);
        }
        List<PathSandbox.Root> roots = sandbox.roots();
        if (roots.size() == 1) {
            return sandbox.resolve(roots.get(0).configured());
        }
        throw new ToolInputException(
                "no path given and " + roots.size() + " roots are configured — pass a path scope");
    }

    private Pattern compile(String query, boolean regex, boolean caseSensitive) {
        String source = regex ? query : Pattern.quote(query);
        if (regex && query.length() > MAX_PATTERN_LENGTH) {
            throw new ToolInputException("regex exceeds " + MAX_PATTERN_LENGTH + " characters");
        }
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(source, flags);
        } catch (PatternSyntaxException e) {
            throw new ToolInputException("invalid regex: " + e.getDescription());
        }
    }

    private void scan(Path file, Path displayBase, Pattern pattern, StringBuilder out, Stats stats)
            throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.length() > props.maxSearchLineLength()) {
                    continue; // long lines: skipped, regex cost on them is unbounded
                }
                var matcher = pattern.matcher(line);
                if (matcher.find()) {
                    stats.matches++;
                    if (out.length() < 32_000) { // soft output budget
                        out.append(displayPath(file, displayBase)).append(':').append(lineNo)
                                .append(": ").append(trim(line)).append('\n');
                    }
                    if (stats.matches >= props.maxSearchMatches()) {
                        stats.matchCapHit = true;
                        return;
                    }
                }
            }
        }
    }

    private final class Stats {
        int matches;
        int filesVisited;
        int bigSkipped;
        int binarySkipped;
        int unreadable;
        boolean matchCapHit;
        boolean fileCapHit;
        boolean deadlineHit;
    }

    /** Relativized display path; falls back to the bare file name across roots. */
    private String displayPath(Path file, Path displayBase) {
        try {
            return displayBase.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName() == null ? file.toString() : file.getFileName().toString();
        }
    }

    private static String trim(String line) {
        String t = line.strip();
        return t.length() <= MAX_SNIPPET_LENGTH ? t : t.substring(0, MAX_SNIPPET_LENGTH) + "…";
    }

    private void appendSummary(StringBuilder out, Stats stats) {
        out.append("— searched ").append(stats.filesVisited).append(" files, ")
                .append(stats.matches).append(" matches");
        var notes = new ArrayList<String>();
        if (stats.matchCapHit) {
            notes.add("match cap " + props.maxSearchMatches() + " reached");
        }
        if (stats.fileCapHit) {
            notes.add("file cap " + props.maxSearchFiles() + " reached");
        }
        if (stats.deadlineHit) {
            notes.add("deadline " + props.searchDeadlineMillis() + "ms reached");
        }
        if (stats.bigSkipped > 0) {
            notes.add(stats.bigSkipped + " oversized files skipped");
        }
        if (stats.binarySkipped > 0) {
            notes.add(stats.binarySkipped + " binary files skipped");
        }
        if (stats.unreadable > 0) {
            notes.add(stats.unreadable + " unreadable");
        }
        if (!notes.isEmpty()) {
            out.append(" (").append(String.join(", ", notes)).append(')');
        }
        out.append('\n');
    }
}
