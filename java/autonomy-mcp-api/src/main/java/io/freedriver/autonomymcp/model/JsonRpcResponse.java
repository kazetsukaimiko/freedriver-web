package io.freedriver.autonomymcp.model;

/** JSON-RPC 2.0 success envelope. */
public record JsonRpcResponse(String jsonrpc, Object id, Object result) {
}
