package com.mcpdev.tooling.tools.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.mcpdev.tooling.config.FileSystemProperties;
import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolInputException;
import com.mcpdev.tooling.security.PathSandbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FsToolsTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private PathSandbox sandbox;
    private FileSystemProperties props;
    private ListDirTool listDir;
    private ReadFileTool readFile;
    private SearchCodeTool search;

    @BeforeEach
    void setUp() throws IOException {
        props = FileSystemProperties.withDefaults(List.of(root.toString()));
        sandbox = new PathSandbox(props.roots());
        listDir = new ListDirTool(sandbox, props);
        readFile = new ReadFileTool(sandbox, props);
        search = new SearchCodeTool(sandbox, props);

        Files.writeString(root.resolve("Main.java"), "public class Main {\n  int needle = 1;\n}\n");
        Files.writeString(root.resolve("notes.md"), "the needle is here too\n");
        Files.createDirectories(root.resolve("src/deep"));
        Files.writeString(root.resolve("src/App.java"), "// needle in src\n");
        Files.writeString(root.resolve("src/deep/X.java"), "needle deep\n");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "needle in git config\n");
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("node_modules/pkg/index.js"), "needle in node_modules\n");
    }

    // --- guard contract (Mockito): no tool touches the FS before sandbox approval ----

    @Test
    @DisplayName("tools consult the sandbox first and propagate its rejection")
    void guardContract() throws IOException {
        for (var tool : List.of(listDir, readFile, search)) {
            PathSandbox spied = Mockito.mock(PathSandbox.class);
            when(spied.resolve(anyString())).thenThrow(
                    new ToolExecutionException("path is outside the allowed filesystem roots"));
            var guarded = new ListDirTool(spied, props);
            var guardedRead = new ReadFileTool(spied, props);
            var guardedSearch = new SearchCodeTool(spied, props);

            assertThatThrownBy(() -> guarded.execute(new ListDirTool.Args("/etc", Optional.empty())))
                    .isInstanceOf(ToolExecutionException.class);
            assertThatThrownBy(() -> guardedRead.execute(new ReadFileTool.Args("/etc/passwd")))
                    .isInstanceOf(ToolExecutionException.class);
            assertThatThrownBy(() -> guardedSearch.execute(new SearchCodeTool.Args(
                    "x", Optional.of("/etc"), Optional.empty(), Optional.empty(), Optional.empty())))
                    .isInstanceOf(ToolExecutionException.class);

            verify(spied, Mockito.times(3)).resolve(anyString());
        }
    }

    @Test
    @DisplayName("deny-by-default: no configured roots fails every tool")
    void denyByDefault() throws IOException {
        var disabled = new PathSandbox(List.of());
        var t = new ReadFileTool(disabled, props);
        assertThatThrownBy(() -> t.execute(new ReadFileTool.Args("/anything")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("disabled");
    }

    // --- list_dir ---------------------------------------------------------------------

    @Test
    @DisplayName("list_dir shows entries, hides ignored dirs, marks links, respects depth")
    void listDirBasics() throws IOException {
        Path victim = Files.writeString(outside.resolve("secret.txt"), "leak");
        Files.createSymbolicLink(root.resolve("escape"), victim);

        String flat = listDir.execute(new ListDirTool.Args(root.toString(), Optional.empty())).text();
        assertThat(flat)
                .contains("src/")
                .contains("Main.java")
                .contains("escape ->")
                .doesNotContain(".git")
                .doesNotContain("node_modules");

        String deep = listDir.execute(new ListDirTool.Args(root.toString(), Optional.of(3))).text();
        assertThat(deep).contains("src/deep");
    }

    @Test
    @DisplayName("list_dir entry cap truncates with marker")
    void listDirCap() throws IOException {
        for (int i = 0; i < 520; i++) {
            Files.writeString(root.resolve("f" + i + ".txt"), "x");
        }
        String out = listDir.execute(new ListDirTool.Args(root.toString(), Optional.empty())).text();
        assertThat(out).contains("truncated at 500 entries");
    }

    @Test
    @DisplayName("list_dir rejects depth above max and non-directories")
    void listDirValidation() {
        assertThatThrownBy(() -> listDir.execute(new ListDirTool.Args(root.toString(), Optional.of(9))))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> listDir.execute(
                new ListDirTool.Args(root.resolve("Main.java").toString(), Optional.empty())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not a directory");
    }

    // --- read_file --------------------------------------------------------------------

    @Test
    @DisplayName("read_file returns content; missing/binary handled; caps truncate")
    void readFileBasics() throws IOException {
        String text = readFile.execute(
                new ReadFileTool.Args(root.resolve("Main.java").toString())).text();
        assertThat(text).contains("needle");

        assertThatThrownBy(() -> readFile.execute(new ReadFileTool.Args(root.resolve("nope").toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not found");

        Path dir = root.resolve("src");
        assertThatThrownBy(() -> readFile.execute(new ReadFileTool.Args(dir.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("use list_dir");

        Path binary = root.resolve("blob.bin");
        Files.write(binary, new byte[]{1, 0, 2, 0, 3});
        assertThatThrownBy(() -> readFile.execute(new ReadFileTool.Args(binary.toString())))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("binary");

        Path big = root.resolve("big.txt");
        Files.writeString(big, ("x".repeat(120) + "\n").repeat(2100), StandardCharsets.UTF_8);
        String truncated = readFile.execute(new ReadFileTool.Args(big.toString())).text();
        assertThat(truncated).contains("[showing 2000 of 2101 lines]");
    }

    // --- search_code ------------------------------------------------------------------

    @Test
    @DisplayName("search finds matches everywhere, prunes ignored dirs, literal default")
    void searchBasics() throws IOException {
        String out = search.execute(new SearchCodeTool.Args(
                "needle", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty())).text();

        assertThat(out)
                .contains("Main.java")
                .contains("notes.md")
                .contains("src/App.java")
                .contains("src/deep/X.java")
                .doesNotContain(".git")
                .doesNotContain("node_modules")
                .contains("4 matches");
    }

    @Test
    @DisplayName("search: glob filter, case sensitivity, regex mode, invalid regex")
    void searchFilters() throws IOException {
        String javaOnly = search.execute(new SearchCodeTool.Args(
                "needle", Optional.empty(), Optional.of("*.java"), Optional.empty(),
                Optional.empty())).text();
        assertThat(javaOnly).contains("Main.java").doesNotContain("notes.md");

        String upperOnly = search.execute(new SearchCodeTool.Args(
                "NEEDLE", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(true))).text();
        assertThat(upperOnly).contains("0 matches");

        String regex = search.execute(new SearchCodeTool.Args(
                "n.e+dl[e]", Optional.empty(), Optional.empty(), Optional.of(true),
                Optional.empty())).text();
        assertThat(regex).contains("Main.java");

        assertThatThrownBy(() -> search.execute(new SearchCodeTool.Args(
                "([unclosed", Optional.empty(), Optional.empty(), Optional.of(true),
                Optional.empty()))).isInstanceOf(ToolInputException.class);
    }

    @Test
    @DisplayName("search caps: match cap terminates with marker, big files skipped")
    void searchCaps() throws IOException {
        for (int i = 0; i < 150; i++) {
            Files.writeString(root.resolve("m" + i + ".txt"), "needle here\n");
        }
        String out = search.execute(new SearchCodeTool.Args(
                "needle", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty())).text();
        assertThat(out).contains("match cap 100 reached");

        Path big = root.resolve("huge.txt");
        Files.write(big, new byte[600_000]); // over 524288 cap -> skipped by search
        String out2 = search.execute(new SearchCodeTool.Args(
                "x", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())).text();
        assertThat(out2).contains("1 oversized files skipped");
    }

    @Test
    @DisplayName("search scope narrows to a subdirectory; blank query rejected")
    void searchScope() throws IOException {
        String out = search.execute(new SearchCodeTool.Args(
                "needle", Optional.of(root.resolve("src").toString()), Optional.empty(),
                Optional.empty(), Optional.empty())).text();
        assertThat(out).contains("App.java").doesNotContain("notes.md");

        assertThatThrownBy(() -> search.execute(new SearchCodeTool.Args(
                "  ", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())))
                .isInstanceOf(ToolInputException.class);
    }
}
