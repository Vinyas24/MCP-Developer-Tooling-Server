package com.mcpdev.tooling.tools;

import com.mcpdev.tooling.registry.ToolInputException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolsTest {

    private final EchoTool echo = new EchoTool();
    private final TimeTool time = new TimeTool();

    @Test
    @DisplayName("echo repeats; default repeat=1; bounds enforced as ToolInputException")
    void echo() {
        assertThat(echo.execute(new EchoTool.Args("hi", Optional.of(2))).text()).isEqualTo("hi\nhi");
        assertThat(echo.execute(new EchoTool.Args("hi", Optional.empty())).text()).isEqualTo("hi");
        assertThat(echo.execute(new EchoTool.Args("hi", Optional.of(1))).isError()).isFalse();

        assertThatThrownBy(() -> echo.execute(new EchoTool.Args("hi", Optional.of(0))))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> echo.execute(new EchoTool.Args("hi", Optional.of(51))))
                .isInstanceOf(ToolInputException.class)
                .hasMessageContaining("between 1 and 50");
    }

    @Test
    @DisplayName("time defaults to UTC and accepts/rejects zone ids")
    void time() {
        String utc = time.execute(new TimeTool.Args(java.util.Optional.empty())).text();
        assertThat(utc).endsWith("Z");

        String berlin = time.execute(new TimeTool.Args(java.util.Optional.of("Europe/Berlin"))).text();
        assertThat(berlin).containsAnyOf("+01:00", "+02:00").doesNotEndWith("Z");

        assertThatThrownBy(() -> time.execute(new TimeTool.Args(java.util.Optional.of("Nope/Zone"))))
                .isInstanceOf(ToolInputException.class);
    }
}
