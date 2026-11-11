package io.freedriver.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP protocol outcome. {@link McpProtocolExceptionMapper} owns the HTTP status
 * and the JSON-RPC error body. Detail strings are fixed and do not include the prompt.
 */
public final class McpProtocolException extends RuntimeException {

    public enum Outcome {
        PARSE(400, -32700),
        INVALID(400, -32600),
        NOT_FOUND(404, -32600),
        FORBIDDEN(403, -32600),
        METHOD_NOT_FOUND(200, -32601),
        INVALID_PARAMS(200, -32602),
        ACCEPTED(202, 0),
        NOT_ALLOWED(405, 0);

        private final int status;
        private final int code;

        Outcome(int status, int code) {
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public int code() {
            return code;
        }
    }

    private final Outcome outcome;
    private final Object id;
    private final String detail;

    private McpProtocolException(Outcome outcome, Object id, String detail) {
        super(detail == null ? outcome.name() : detail);
        this.outcome = outcome;
        this.id = id;
        this.detail = detail;
    }

    public static McpProtocolException parse() {
        return new McpProtocolException(Outcome.PARSE, null, "parse error");
    }

    public static McpProtocolException invalid(Object id, String detail) {
        return new McpProtocolException(Outcome.INVALID, id(id), detail);
    }

    public static McpProtocolException notFound(Object id) {
        return new McpProtocolException(Outcome.NOT_FOUND, id(id), "session not found");
    }

    public static McpProtocolException forbidden(Object id) {
        return new McpProtocolException(Outcome.FORBIDDEN, id(id), "session belongs to another subject");
    }

    public static McpProtocolException methodNotFound(Object id) {
        return new McpProtocolException(Outcome.METHOD_NOT_FOUND, id(id), "method not found");
    }

    public static McpProtocolException invalidParams(Object id, String detail) {
        return new McpProtocolException(Outcome.INVALID_PARAMS, id(id), detail);
    }

    public static McpProtocolException accepted() {
        return new McpProtocolException(Outcome.ACCEPTED, null, null);
    }

    public static McpProtocolException notAllowed() {
        return new McpProtocolException(Outcome.NOT_ALLOWED, null, null);
    }

    public Outcome outcome() {
        return outcome;
    }

    public Object id() {
        return id;
    }

    public String detail() {
        return detail;
    }

    private static Object id(Object id) {
        if (id instanceof JsonNode node && (node.isNull() || node.isMissingNode())) {
            return null;
        }
        return id;
    }
}
