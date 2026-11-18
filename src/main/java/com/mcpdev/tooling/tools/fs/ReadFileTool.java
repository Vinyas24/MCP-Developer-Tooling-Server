package com.mcpdev.tooling.tools.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mcpdev.tooling.config.FileSystemProperties;
import com.mcpdev.tooling.registry.Tool;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolResult;
import com.mcpdev.tooling.security.PathSandbox;
import org.springframework.stereotype.Component;

/**
 * Reads a text file with hard byte and line caps. Binary files are rejected, not
 * mangled; oversized files are truncated with an explicit marker.
 */
@Component
public class ReadFileTool implements Tool<ReadFileTool.Args> {

    public record Args(String path) {
    }

    private final PathSandbox sandbox;
    private final FileSystemProperties props;

    public ReadFileTool(PathSandbox sandbox, FileSystemProperties props) {
        this.sandbox = sandbox;
        this.props = props;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Reads a UTF-8 text file inside an allowed root. Large files are truncated "
                + "(markers show what was cut); binary files are rejected.";
    }

    @Override
    public Class<Args> requestType() {
        return Args.class;
    }

    @Override
    public ToolResult execute(Args args) {
        PathSandbox.Resolved resolved = sandbox.resolve(args.path());
        Path file = resolved.real();

        if (!Files.isRegularFile(file)) {
            if (Files.isDirectory(file)) {
                throw new ToolExecutionException("not a file (use list_dir): " + args.path());
            }
            throw new ToolExecutionException("file not found: " + args.path());
        }

        try {
            if (FsSupport.looksBinary(file)) {
                throw new ToolExecutionException(
                        "binary file refused: " + args.path());
            }

            FsSupport.Content content = FsSupport.readCapped(file, props.maxFileBytes());
            String text = content.text();

            String[] lines = text.split("\n", -1);
            if (lines.length > props.maxLines()) {
                text = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, props.maxLines()))
                        + "\n… [showing " + props.maxLines() + " of " + lines.length + " lines]";
            }

            if (content.truncated()) {
                text = text + "\n… [truncated at " + props.maxFileBytes()
                        + " of " + content.totalSize() + " bytes]";
            }
            return ToolResult.text(text);
        } catch (IOException e) {
            throw new ToolExecutionException("cannot read file: " + e.getClass().getSimpleName());
        }
    }
}
