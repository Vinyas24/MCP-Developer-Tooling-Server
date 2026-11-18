package com.mcpdev.tooling.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import com.mcpdev.tooling.executor.ToolExecutor;
import com.mcpdev.tooling.protocol.ProtocolKeys;
import com.mcpdev.tooling.security.AuditLog;
import com.mcpdev.tooling.security.DomainChecks;
import com.mcpdev.tooling.security.EffectivePermissions;
import com.mcpdev.tooling.security.GuardChain;
import com.mcpdev.tooling.security.Identity;
import com.mcpdev.tooling.security.IdentityGuard;
import com.mcpdev.tooling.security.PathSandbox;
import com.mcpdev.tooling.security.PermissionGuard;
import com.mcpdev.tooling.security.RateLimitGuard;
import com.mcpdev.tooling.security.Role;
import com.mcpdev.tooling.security.SizeLimitGuard;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6: the funnel is async. Verifies virtual-thread execution, real concurrency,
 * per-call deadline, and cancellation through the JSON-RPC request id — the three
 * things the SDK's sync bridge cannot do.
 */
class McpToolAdapterAsyncTest {

    @TempDir
    Path tempDir;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private ToolExecutor executor;

    record Empty() {
    }

    private Tool<Empty> tool(String name, Supplier<ToolResult> body) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Class<Empty> requestType() {
                return Empty.class;
            }

            @Override
            public ToolResult execute(Empty args) {
                return body.get();
            }
        };
    }

    private McpToolAdapter adapterFor(ToolExecutor ex, Tool<?>... tools) throws IOException {
        var perms = new EffectivePermissions(tools, Map.of());
        var chain = new GuardChain(List.of(
                new IdentityGuard(() -> Identity.of("async-test", Role.ADMIN)),
                new PermissionGuard(perms),
                new RateLimitGuard(10_000, 1_000_000),
                new SizeLimitGuard(1_000_000)));
        return new McpToolAdapter(mapper, chain,
                new DomainChecks(new PathSandbox(List.of())),
                new AuditLog(tempDir.resolve("audit.log")), ex);
    }

    private boolean awaitAudit(String needle) throws InterruptedException {
        Path file = tempDir.resolve("audit.log");
        for (int i = 0; i < 100; i++) {
            try {
                if (Files.exists(file) && Files.readString(file).contains(needle)) {
                    return true;
                }
            } catch (IOException ignored) {
                // file being appended; retry
            }
            Thread.sleep(20);
        }
        return false;
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    private static McpAsyncServerExchange exchange(Object requestId) {
        return new McpAsyncServerExchange("session-1", null, null, null,
                McpTransportContext.create(Map.of(ProtocolKeys.REQUEST_ID, requestId)));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    @DisplayName("tool code runs on a virtual thread, not a Reactor carrier")
    void virtualThread() throws IOException {
        executor = new ToolExecutor(16, 200, 5_000, 0);
        AtomicBoolean virtual = new AtomicBoolean();
        var tool = tool("vt", () -> {
            virtual.set(Thread.currentThread().isVirtual());
            return ToolResult.text("ok");
        });
        var adapter = adapterFor(executor, tool);

        McpSchema.CallToolResult result = adapter.toSpecification(tool)
                .callHandler().apply(exchange(1), new McpSchema.CallToolRequest("vt", Map.of()))
                .block(java.time.Duration.ofSeconds(5));

        assertThat(virtual).isTrue();
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("ok");
    }

    @Test
    @DisplayName("100 calls overlap: concurrency is bounded by permits, not by dispatch")
    void concurrency() throws Exception {
        executor = new ToolExecutor(128, 1_000, 10_000, 0);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allInside = new CountDownLatch(100);

        var tool = tool("slow", () -> {
            int now = current.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            allInside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            current.decrementAndGet();
            return ToolResult.text("ok");
        });
        var adapter = adapterFor(executor, tool);
        McpServerFeatures.AsyncToolSpecification spec = adapter.toSpecification(tool);

        CountDownLatch done = new CountDownLatch(100);
        for (int i = 0; i < 100; i++) {
            final int id = i;
            spec.callHandler().apply(exchange(id), new McpSchema.CallToolRequest("slow", Map.of()))
                    .subscribe(r -> done.countDown(), e -> done.countDown());
        }

        assertThat(allInside.await(10, TimeUnit.SECONDS))
                .as("100 calls should be executing at the same time")
                .isTrue();
        assertThat(peak.get()).isEqualTo(100);

        release.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.stats().permitsInUse()).isZero();
    }

    @Test
    @DisplayName("deadline: call returns a timeout error and is audited as TIMEOUT")
    void timeout() throws Exception {
        executor = new ToolExecutor(8, 200, 150, 0);
        var tool = tool("park", () -> {
            while (!Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(5_000_000);
            }
            return ToolResult.text("late");
        });
        var adapter = adapterFor(executor, tool);

        McpSchema.CallToolResult result = adapter.toSpecification(tool)
                .callHandler().apply(exchange(7), new McpSchema.CallToolRequest("park", Map.of()))
                .block(java.time.Duration.ofSeconds(5));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("timed out after 150ms");
        // the response is immediate; the single audit line lands when the worker unwinds
        assertThat(awaitAudit("\"outcome\":\"TIMEOUT\"")).isTrue();
        assertThat(Files.readString(tempDir.resolve("audit.log")))
                .contains("\"correlation\":\"7\"");
        assertThat(executor.stats().timedOut()).isEqualTo(1);
    }

    @Test
    @DisplayName("notifications/cancelled semantics: cancel by request id stops the call, audited CANCELLED")
    void cancellation() throws Exception {
        executor = new ToolExecutor(8, 200, 10_000, 0);
        CountDownLatch running = new CountDownLatch(1);
        var tool = tool("park", () -> {
            running.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(5_000_000);
            }
            return ToolResult.text("stopped");
        });
        var adapter = adapterFor(executor, tool);

        var mono = adapter.toSpecification(tool)
                .callHandler().apply(exchange(99), new McpSchema.CallToolRequest("park", Map.of()));
        var resultRef = new java.util.concurrent.atomic.AtomicReference<McpSchema.CallToolResult>();
        var finished = new CountDownLatch(1);
        executor.scheduler().schedule(() -> {
            try {
                resultRef.set(mono.block(java.time.Duration.ofSeconds(5)));
            } finally {
                finished.countDown();
            }
        });

        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.cancel("99")).isTrue();

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        McpSchema.CallToolResult result = resultRef.get();
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("cancelled by the client");
        assertThat(awaitAudit("\"outcome\":\"CANCELLED\"")).isTrue();
        assertThat(executor.stats().cancelled()).isEqualTo(1);
    }
}
