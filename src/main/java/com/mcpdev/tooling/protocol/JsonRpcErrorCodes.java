package com.mcpdev.tooling.protocol;

/**
 * JSON-RPC 2.0 / MCP error codes, with the verified SDK 2.1.0 behavior matrix this
 * server currently exhibits (Phase 2 probing, 2026-11). Do not guess — re-verify
 * after SDK upgrades.
 *
 * <p>Verified behavior:</p>
 * <ul>
 *   <li>unknown tool name       -> JSON-RPC error -32602 (SDK)</li>
 *   <li>schema-invalid arguments-> CallToolResult isError=true (SDK, spec-conformant)</li>
 *   <li>unknown method          -> JSON-RPC error -32601 (SDK)</li>
 *   <li>JSON-RPC-level ping     -> empty result {} (SDK)</li>
 *   <li>tools/call with null params -> -32603 with raw Java NPE text (SDK defect; our
 *       handler never throws so this path only triggers on SDK-internal breakage)</li>
 *   <li>malformed JSON line on stdio -> reader thread dies, session ends silently
 *       (transport hardening planned for Phase 6)</li>
 * </ul>
 */
public final class JsonRpcErrorCodes {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private JsonRpcErrorCodes() {
    }
}
