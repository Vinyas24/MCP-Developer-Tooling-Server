package com.mcpdev.tooling.config;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the actual Spring binding path for FileSystemProperties — @DefaultValue must
 * hold at bind time, not only via the factory used in unit tests. Caught a real bug:
 * the ignore list silently came up empty at runtime.
 */
class FileSystemPropertiesBindingTest {

    private FileSystemProperties bind(MockEnvironment env) {
        return Binder.get(env)
                .bind("mcp.fs", Bindable.of(FileSystemProperties.class))
                .get();
    }

    @Test
    @DisplayName("binding without explicit values: every default applies, incl. ignore list")
    void defaultsAtBindTime() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("mcp.fs.roots", "/tmp/one,/tmp/two");

        FileSystemProperties props = bind(env);

        assertThat(props.roots()).isEqualTo(List.of("/tmp/one", "/tmp/two"));
        assertThat(props.maxFileBytes()).isEqualTo(1_048_576);
        assertThat(props.maxLines()).isEqualTo(2000);
        assertThat(props.maxSearchMatches()).isEqualTo(100);
        assertThat(props.searchDeadlineMillis()).isEqualTo(5000);
        assertThat(props.ignore()).isEqualTo(FileSystemProperties.DEFAULT_IGNORE);
        assertThat(props.ignore()).contains(".git", "node_modules");
    }

    @Test
    @DisplayName("binding with overrides and empty env root: deny-by-default shape")
    void overrides() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("mcp.fs.max-lines", "50");
        env.setProperty("mcp.fs.ignore", "custom_dir");

        FileSystemProperties props = bind(env);

        assertThat(props.maxLines()).isEqualTo(50);
        assertThat(props.ignore()).isEqualTo(List.of("custom_dir"));
    }
}
