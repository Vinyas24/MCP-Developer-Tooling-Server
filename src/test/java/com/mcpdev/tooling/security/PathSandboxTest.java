package com.mcpdev.tooling.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mcpdev.tooling.registry.ToolExecutionException;
import com.mcpdev.tooling.registry.ToolInputException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Attack matrix for path containment. Every case here is a real exploit pattern. */
class PathSandboxTest {

    @TempDir
    Path root;

    @TempDir
    Path outside;

    private PathSandbox sandboxOf(String... roots) {
        return new PathSandbox(List.of(roots));
    }

    private PathSandbox single() throws IOException {
        Files.writeString(root.resolve("secret.txt"), "hello");
        Files.createDirectories(root.resolve("a/b"));
        Files.writeString(root.resolve("a/b/c.txt"), "deep");
        return sandboxOf(root.toString());
    }

    @Test
    @DisplayName("plain path inside root resolves and real == display for non-links")
    void insideOk() throws IOException {
        var s = single();
        var r = s.resolve(root.resolve("a/b/c.txt").toString());
        assertThat(r.real()).isEqualTo(root.resolve("a/b/c.txt"));
        assertThat(r.root().configured()).isEqualTo(root.toString());
    }

    @Test
    @DisplayName("dot-dot traversal is rejected")
    void traversal() throws IOException {
        var s = single();
        String p = root.resolve("a/b") + "/../../../" + root.getFileName() + "/../"
                + outside.getFileName() + "/x.txt";
        // simpler canonical case:
        assertThatThrownBy(() -> s.resolve(root.resolve("a/b/../..").resolve("../..")
                .resolve(outside.getFileName()).resolve("x.txt").toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("absolute path to a file outside any root is rejected")
    void outsideRoot() throws IOException {
        var s = single();
        Path victim = Files.writeString(outside.resolve("x.txt"), "leak");
        assertThatThrownBy(() -> s.resolve(victim.toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("symlink pointing outside the root is rejected (real-path layer)")
    void symlinkEscape() throws IOException {
        var s = single();
        Path victim = Files.writeString(outside.resolve("x.txt"), "leak");
        Files.createSymbolicLink(root.resolve("escape"), victim);
        assertThatThrownBy(() -> s.resolve(root.resolve("escape").toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("symbolic links");
    }

    @Test
    @DisplayName("symlink leaving and re-entering the root is allowed: containment is target-based")
    void symlinkReenter() throws IOException {
        var s = single();
        Files.createSymbolicLink(outside.resolve("reentry"), root.resolve("a/b/c.txt"));
        Files.createSymbolicLink(root.resolve("hop"), outside);
        var r = s.resolve(root.resolve("hop").resolve("reentry").toString());
        assertThat(r.real()).isEqualTo(root.resolve("a/b/c.txt"));
    }

    @Test
    @DisplayName("symlink inside the root is allowed and resolves to its target")
    void symlinkInside() throws IOException {
        var s = single();
        Files.createSymbolicLink(root.resolve("a/link"), root.resolve("secret.txt"));
        var r = s.resolve(root.resolve("a/link").toString());
        assertThat(r.real()).isEqualTo(root.resolve("secret.txt"));
    }

    @Test
    @DisplayName("dot-dot inside the root that stays inside is allowed")
    void traversalStayingInside() throws IOException {
        var s = single();
        var r = s.resolve(root.resolve("a/b/../..").resolve("secret.txt").toString());
        assertThat(r.real()).isEqualTo(root.resolve("secret.txt"));
    }

    @Test
    @DisplayName("nonexistent path inside root: not-found, not 'outside'")
    void notFound() throws IOException {
        var s = single();
        assertThatThrownBy(() -> s.resolve(root.resolve("nope.txt").toString()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("relative path resolves against a single root")
    void relativeSingleRoot() throws IOException {
        var s = single();
        var r = s.resolve("a/b/c.txt");
        assertThat(r.real()).isEqualTo(root.resolve("a/b/c.txt"));
    }

    @Test
    @DisplayName("relative path with multiple roots requires an absolute path")
    void relativeMultiRoot() throws IOException {
        var s = sandboxOf(root.toString(), outside.toString());
        assertThatThrownBy(() -> s.resolve("a/b/c.txt"))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    @DisplayName("most specific root wins when roots nest")
    void nestedRoots() throws IOException {
        Path inner = Files.createDirectories(root.resolve("work"));
        Files.writeString(inner.resolve("f.txt"), "x");
        var s = sandboxOf(root.toString(), inner.toString());
        var r = s.resolve(inner.resolve("f.txt").toString());
        assertThat(r.root().configured()).isEqualTo(inner.toString());
    }

    @Test
    @DisplayName("root configured via symlink: access by real path still allowed")
    void symlinkedRoot() throws IOException {
        Path realRoot = Files.createDirectories(root.resolve("real-repo"));
        Files.writeString(realRoot.resolve("f.txt"), "x");
        Path link = root.resolve("link-repo");
        Files.createSymbolicLink(link, realRoot);

        var viaLink = sandboxOf(link.toString());
        assertThat(viaLink.resolve(realRoot.resolve("f.txt").toString()).real())
                .isEqualTo(realRoot.resolve("f.txt"));
        assertThat(viaLink.resolve("f.txt").real()).isEqualTo(realRoot.resolve("f.txt"));
    }

    @Test
    @DisplayName("null byte and blank input are rejected as invalid, not executed")
    void hostileInput() throws IOException {
        var s = single();
        assertThatThrownBy(() -> s.resolve("secret\u0000.txt")).isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> s.resolve("  ")).isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> s.resolve(null)).isInstanceOf(ToolInputException.class);
    }

    @Test
    @DisplayName("no roots configured: enabled()==false and everything fails closed")
    void denyByDefault() throws IOException {
        var s = new PathSandbox(List.of());
        assertThat(s.enabled()).isFalse();
        assertThatThrownBy(() -> s.resolve("/etc/passwd"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("non-absolute root is a configuration error at startup")
    void invalidRootConfig() {
        assertThatThrownBy(() -> sandboxOf("relative/path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absolute");
    }
}
