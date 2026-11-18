package com.mcpdev.tooling.security;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Size cap runs on the raw serialized size, before any parsing. */
class SizeLimitGuardTest {

    private Guard.Result check(int bytes, int limit) {
        return new SizeLimitGuard(limit)
                .check(new GuardContext(GuardChainTest.namedTool("echo"),
                        Map.of("echo", "x"), bytes, "c"));
    }

    @Test
    @DisplayName("a payload at the limit is allowed (boundary is inclusive)")
    void atLimitAllowed() {
        assertThat(check(100, 100).allowed()).isTrue();
    }

    @Test
    @DisplayName("one byte over the limit is denied as DENIED_SIZE")
    void overLimitDenied() {
        Guard.Result result = check(101, 100);

        assertThat(result.allowed()).isFalse();
        assertThat(result.outcome()).isEqualTo(AuditLog.Outcome.DENIED_SIZE);
        assertThat(result.clientMessage()).contains("100 byte limit");
    }

    @Test
    @DisplayName("an unserializable payload (negative size) fails closed")
    void negativeSizeDenied() {
        assertThat(check(-1, 100).allowed()).isFalse();
    }
}
