package com.mcpdev.tooling.registry;

import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.mcpdev.tooling.executor.CallAbortedException;
import com.mcpdev.tooling.executor.CallState;
import com.mcpdev.tooling.executor.ServerBusyException;
import com.mcpdev.tooling.executor.ToolExecutor;
import com.mcpdev.tooling.protocol.ProtocolKeys;
import com.mcpdev.tooling.security.AuditLog;
import com.mcpdev.tooling.security.DomainChecks;
import com.mcpdev.tooling.security.Guard;
import com.mcpdev.tooling.security.GuardChain;
import com.mcpdev.tooling.security.GuardContext;
import com.mcpdev.tooling.security.Identity;

/**
 * Bridges internal {@link Tool}s to the SDK's async tool dispatch. This is THE
 * interception funnel every call passes through:
 *
 * <pre>executor(permit+deadline) -> guards(identity -> permission -> rate -> size)
 *   -> bind -> domain validation -> execute -> audit</pre>
 *
 * <p>Every terminal path (allow or deny, including timeout/cancel/backpressure) writes
 * exactly one audit line; a failed audit write itself denies the call. Client-visible
 * messages never contain internal detail.
 *
 * <p>Async by design since Phase 6: the Mono subscribes on a virtual thread, so 1,000
 * concurrent calls each get a real thread while Reactor's carriers stay free. The
 * JSON-RPC request id arrives through the transport context (the SDK does not expose
 * it to call handlers) and is what makes {@code notifications/cancelled} work.
 */
@Component
public class McpToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private final JsonMapper mapper;
    private final ToolArgsBinder binder;
    private final GuardChain guards;
    private final DomainChecks domainChecks;
    private final AuditLog audit;
    private final ToolExecutor executor;

    McpToolAdapter(@Qualifier("toolJsonMapper") JsonMapper toolJsonMapper,
                   GuardChain guards, DomainChecks domainChecks, AuditLog audit,
                   ToolExecutor executor) {
        this.mapper = toolJsonMapper;
        this.binder = new ToolArgsBinder(toolJsonMapper);
        this.guards = guards;
        this.domainChecks = domainChecks;
        this.audit = audit;
        this.executor = executor;
    }

    public <P> McpServerFeatures.AsyncToolSpecification toSpecification(Tool<P> tool) {
        McpSchema.Tool schema = McpSchema.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(ToolSchemas.generate(tool.requestType()))
                // Explicit, not defaults: SDK defaults claim destructiveHint=true,
                // which poisons client-side guardrails for read-only tools.
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .build())
                .build();

        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(schema)
                .callHandler((exchange, request) ->
                        invokeAsync(tool, request.arguments(), exchange))
                .build();
    }

    private <P> Mono<McpSchema.CallToolResult> invokeAsync(Tool<P> tool,
                                                         Map<String, Object> rawArguments,
                                                         McpAsyncServerExchange exchange) {
        String callId = callId(exchange);
        Map<String, Object> raw = rawArguments == null ? Map.of() : rawArguments;
        ToolExecutor.Handle handle = executor.register(callId);

        return Mono.create(sink -> {
            // Answer an abort (timeout/cancel) IMMEDIATELY, even when the tool ignores
            // the interrupt (JDBC). The worker still audits exactly one line when it
            // eventually unwinds — responses and audits are deliberately decoupled.
            handle.onAbort(() -> sink.success(abortResult(tool, handle.state())));

            executor.scheduler().schedule(() -> {
                long start = System.nanoTime();
                try {
                    sink.success(executor.execute(handle,
                            () -> invoke(tool, raw, callId, handle.state(), start)));
                } catch (ServerBusyException e) {
                    sink.success(audited(callId, null, tool, raw,
                            AuditLog.Outcome.DENIED_BACKPRESSURE, start,
                            errorResult(e.getMessage())));
                } catch (CallAbortedException e) {
                    AuditLog.Outcome outcome = e.reason() == CallState.Reason.TIMEOUT
                            ? AuditLog.Outcome.TIMEOUT : AuditLog.Outcome.CANCELLED;
                    sink.success(audited(callId, null, tool, raw, outcome, start,
                            errorResult(abortMessage(tool, e))));
                }
            });
        });
    }

    private McpSchema.CallToolResult abortResult(Tool<?> tool, CallState state) {
        boolean timeout = state.reason() == CallState.Reason.TIMEOUT;
        return errorResult(timeout
                ? "tool '" + tool.name() + "' timed out after "
                + executor.callTimeoutMillis() + "ms"
                : "tool '" + tool.name() + "' was cancelled by the client");
    }

    /**
     * Synchronous funnel body. Also the unit-test seam: no executor, no transport.
     */
    <P> McpSchema.CallToolResult invoke(Tool<P> tool, Map<String, Object> rawArguments) {
        String callId = "local-" + UUID.randomUUID();
        return invoke(tool, rawArguments, callId, new CallState(), System.nanoTime());
    }

    private <P> McpSchema.CallToolResult invoke(Tool<P> tool, Map<String, Object> raw,
                                               String callId, CallState state, long start) {
        Identity identity = null;
        try {
            GuardContext context = new GuardContext(tool, raw, serializedSize(raw), callId);

            Guard.Result guardResult = guards.check(context);
            identity = context.identity();
            if (!guardResult.allowed()) {
                return audited(callId, identity, tool, raw,
                        guardResult.outcome(), start, errorResult(guardResult.clientMessage()));
            }

            P args = binder.bind(tool, raw);

            try {
                domainChecks.validate(args);
            } catch (ToolException e) {
                return audited(callId, identity, tool, raw,
                        AuditLog.Outcome.DENIED_DOMAIN, start,
                        errorResult("denied: " + e.getMessage()));
            }

            if (state.aborted()) {
                return abortedResult(callId, identity, tool, raw, state, start);
            }

            ToolResult result = tool.execute(args);
            if (state.aborted()) {
                return abortedResult(callId, identity, tool, raw, state, start);
            }
            AuditLog.Outcome outcome = result.isError()
                    ? AuditLog.Outcome.TOOL_ERROR : AuditLog.Outcome.OK;
            return audited(callId, identity, tool, raw, outcome, start, asResult(result));
        } catch (ToolInputException e) {
            return audited(callId, identity, tool, raw,
                    AuditLog.Outcome.INVALID_ARGS, start, errorResult(e.getMessage()));
        } catch (ToolExecutionException e) {
            // an abort may surface as a tool-level error (interrupted IO)
            if (state.aborted()) {
                return abortedResult(callId, identity, tool, raw, state, start);
            }
            return audited(callId, identity, tool, raw,
                    AuditLog.Outcome.TOOL_ERROR, start, errorResult(e.getMessage()));
        } catch (JacksonException e) {
            return audited(callId, identity, tool, raw,
                    AuditLog.Outcome.INVALID_ARGS, start,
                    errorResult("Invalid arguments for tool '" + tool.name()
                            + "': " + rootMessage(e)));
        } catch (Exception e) {
            if (state.aborted()) {
                return abortedResult(callId, identity, tool, raw, state, start);
            }
            log.error("Tool '{}' failed internally [correlation={}]", tool.name(), callId, e);
            return audited(callId, identity, tool, raw,
                    AuditLog.Outcome.INTERNAL_ERROR, start,
                    errorResult("Internal error in tool '" + tool.name()
                            + "' (correlation=" + callId + ")"));
        } finally {
            // close the deadline race: a finished call can never be re-labeled timeout
            state.complete();
        }
    }

    private McpSchema.CallToolResult abortedResult(String callId, Identity identity,
                                                   Tool<?> tool, Map<String, Object> raw,
                                                   CallState state, long start) {
        boolean timeout = state.reason() == CallState.Reason.TIMEOUT;
        return audited(callId, identity, tool, raw,
                timeout ? AuditLog.Outcome.TIMEOUT : AuditLog.Outcome.CANCELLED, start,
                errorResult(timeout
                        ? "tool '" + tool.name() + "' timed out after "
                        + executor.callTimeoutMillis() + "ms"
                        : "tool '" + tool.name() + "' was cancelled by the client"));
    }

    private static String abortMessage(Tool<?> tool, CallAbortedException e) {
        return e.reason() == CallState.Reason.TIMEOUT
                ? "tool '" + tool.name() + "' timed out before it could start"
                : "tool '" + tool.name() + "' was cancelled before it could start";
    }

    private static String callId(McpAsyncServerExchange exchange) {
        if (exchange != null) {
            Object id = exchange.transportContext().get(ProtocolKeys.REQUEST_ID);
            if (id != null) {
                return String.valueOf(id);
            }
        }
        return "auto-" + UUID.randomUUID();
    }

    /** Audits then returns the result; audit failure itself denies the call (fail-closed). */
    private McpSchema.CallToolResult audited(String correlationId, Identity identity,
                                             Tool<?> tool, Map<String, Object> raw,
                                             AuditLog.Outcome outcome, long start,
                                             McpSchema.CallToolResult result) {
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        try {
            audit.write(correlationId,
                    identity == null ? new Identity("unknown", null) : identity,
                    tool.name(), argsHash(raw), outcome, durationMs);
        } catch (Exception auditFailure) {
            log.error("AUDIT WRITE FAILED [correlation={}] — denying call",
                    correlationId, auditFailure);
            return errorResult("denied: request could not be audited");
        }
        return result;
    }

    private int serializedSize(Map<String, Object> raw) {
        try {
            return mapper.writeValueAsString(raw).length();
        } catch (JacksonException e) {
            return Integer.MAX_VALUE; // unserializable args are over the size cap by definition
        }
    }

    private String argsHash(Map<String, Object> raw) {
        try {
            String canonical = mapper.writeValueAsString(raw);
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unhashable";
        }
    }

    private McpSchema.CallToolResult asResult(ToolResult result) {
        var builder = McpSchema.CallToolResult.builder().addTextContent(result.text());
        if (result.structured() != null) {
            builder.structuredContent(result.structured());
        }
        return builder.isError(result.isError()).build();
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private static String rootMessage(Throwable t) {
        // Client-safe scrubbing: Jackson messages embed local class names
        // ("through reference chain: com.x.Args[\"junk\"]) — strip to bare facts.
        if (t instanceof JacksonException) {
            var unrecognized = java.util.regex.Pattern
                    .compile("Unrecognized property \\\"([^\\\"]+)\\\"")
                    .matcher(t.getMessage() == null ? "" : t.getMessage());
            if (unrecognized.find()) {
                return "Unknown argument '" + unrecognized.group(1) + "'";
            }
            return "Invalid argument type";
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? "malformed arguments" : m;
    }
}
