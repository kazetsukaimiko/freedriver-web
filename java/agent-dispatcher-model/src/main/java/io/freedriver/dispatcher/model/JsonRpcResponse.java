package io.freedriver.dispatcher.model;

/**
 * JSON-RPC 2.0 success envelope. {@code id} and {@code result} stay untyped
 * because MCP results are tool-specific JSON.
 */
public record JsonRpcResponse(String jsonrpc, Object id, Object result) {
}
