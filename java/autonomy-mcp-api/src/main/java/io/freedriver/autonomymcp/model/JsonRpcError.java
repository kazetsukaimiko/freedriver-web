package io.freedriver.autonomymcp.model;

/** JSON-RPC 2.0 error envelope. The message is a fixed protocol string. */
public record JsonRpcError(String jsonrpc, Object id, Body error) {

    public record Body(int code, String message) {
    }
}
