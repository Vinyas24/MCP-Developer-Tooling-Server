package com.mcpdev.tooling.protocol;

/**
 * Shared Reactor-context key names. Transport and dispatch both depend on this tiny
 * contract without depending on each other.
 */
public final class ProtocolKeys {

    /** JSON-RPC request id of the in-flight call, injected by the transport. */
    public static final String REQUEST_ID = "jsonrpc.requestId";

    private ProtocolKeys() {
    }
}
