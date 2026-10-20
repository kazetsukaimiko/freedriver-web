package io.freedriver.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.dispatcher.api.Actors;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * Streamable HTTP MCP at {@code /mcp}. Legacy SSE is not mounted.
 * The bearer token is the credential. {@code MCP-Session-Id} only names a session
 * already bound to that token's subject.
 */
@Path("/mcp")
@ApplicationScoped
public class McpResource {

    public static final String SESSION_HEADER = "MCP-Session-Id";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final String LATEST_PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOL_VERSIONS = Set.of("2025-03-26", "2025-06-18", LATEST_PROTOCOL);
    private static final Set<String> SUBMIT_FIELDS = Set.of("deviceIds", "agentName", "prompt", "channel");
    private static final Set<String> JOB_FIELDS = Set.of("jobId");

    private final JobStore jobs;
    private final McpSessions sessions;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Duration submitTimeout;

    @Inject
    public McpResource(
            JobStore jobs,
            McpSessions sessions,
            SecurityIdentity identity,
            ObjectMapper mapper,
            Validator validator,
            @ConfigProperty(name = "freedriver.dispatcher.submit-timeout", defaultValue = "75s") Duration submitTimeout) {
        this.jobs = jobs;
        this.sessions = sessions;
        this.identity = identity;
        this.mapper = mapper;
        this.validator = validator;
        this.submitTimeout = submitTimeout;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed(Actors.DISPATCHER)
    @Blocking
    public Response post(
            @HeaderParam(SESSION_HEADER) String sessionId,
            @HeaderParam("MCP-Protocol-Version") String protocolVersion,
            String body) {
        String subject = CallerIds.subject(identity);
        Response rejected = rejectPresentedSession(sessionId, subject);
        if (rejected != null) {
            return rejected;
        }
        JsonNode message;
        try {
            message = parse(body);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, PARSE_ERROR, "parse error"), null);
        }
        if (!message.isObject()) {
            return json(400, error(null, INVALID_REQUEST, "invalid request"), null);
        }
        JsonNode id = message.get("id");
        if (protocolVersion != null && !protocolVersion.isBlank() && !PROTOCOL_VERSIONS.contains(protocolVersion)) {
            return json(400, error(id, INVALID_REQUEST, "unsupported protocol version"), matchedSession(sessionId, subject));
        }
        String method = text(message.get("method"));
        if (method == null) {
            return json(400, error(id, INVALID_REQUEST, "invalid request"), null);
        }
        if ("initialize".equals(method)) {
            return initialize(subject, id, message.get("params"));
        }
        if (sessions.check(sessionId, subject) != McpSessions.Status.MATCH) {
            return sessionFailure(McpSessions.Status.ABSENT, id);
        }
        if (!message.has("id")) {
            return Response.accepted().header(SESSION_HEADER, sessionId).build();
        }
        return switch (method) {
            case "ping" -> json(200, result(id, mapper.createObjectNode()), sessionId);
            case "tools/list" -> json(200, result(id, toolsList()), sessionId);
            case "tools/call" -> json(200, callTool(subject, id, message.get("params")), sessionId);
            default -> json(200, error(id, METHOD_NOT_FOUND, "method not found"), sessionId);
        };
    }

    @GET
    @RolesAllowed(Actors.DISPATCHER)
    public Response get(@HeaderParam(SESSION_HEADER) String sessionId) {
        String subject = CallerIds.subject(identity);
        Response rejected = rejectPresentedSession(sessionId, subject);
        if (rejected != null) {
            return rejected;
        }
        if (sessions.check(sessionId, subject) != McpSessions.Status.MATCH) {
            return sessionFailure(McpSessions.Status.ABSENT, null);
        }
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).header("Allow", "POST, DELETE").build();
    }

    @DELETE
    @RolesAllowed(Actors.DISPATCHER)
    public Response delete(@HeaderParam(SESSION_HEADER) String sessionId) {
        String subject = CallerIds.subject(identity);
        Response rejected = rejectPresentedSession(sessionId, subject);
        if (rejected != null) {
            return rejected;
        }
        if (sessions.check(sessionId, subject) != McpSessions.Status.MATCH) {
            return sessionFailure(McpSessions.Status.ABSENT, null);
        }
        sessions.close(sessionId, subject);
        return Response.noContent().build();
    }

    private Response initialize(String subject, JsonNode id, JsonNode params) {
        String requested = params != null && params.isObject() ? text(params.get("protocolVersion")) : null;
        String version = requested != null && PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL;
        String sessionId = sessions.open(subject);
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode tools = mapper.createObjectNode();
        tools.put("listChanged", false);
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", "freedriver-dispatcher");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        return json(200, result(id, result), sessionId);
    }

    private ObjectNode callTool(String subject, JsonNode id, JsonNode params) {
        if (params == null || !params.isObject()) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        String name = text(params.get("name"));
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = mapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        try {
            return switch (name) {
                case "list_devices" -> listDevices(id, arguments);
                case "submit_job" -> submitJob(subject, id, arguments);
                case "get_job" -> getJob(subject, id, arguments);
                case null -> error(id, INVALID_PARAMS, "invalid params");
                default -> error(id, INVALID_PARAMS, "unknown tool");
            };
        } catch (DispatcherException ex) {
            return toolError(id, ex.getMessage() == null || ex.getMessage().isBlank() ? "request failed" : ex.getMessage());
        }
    }

    private ObjectNode listDevices(JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        return toolResult(id, write(jobs.listDevices()), false);
    }

    private ObjectNode submitJob(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, SUBMIT_FIELDS)
                || !textual(arguments, "agentName")
                || !textual(arguments, "prompt")
                || !textual(arguments, "channel")
                || !stringArray(arguments.get("deviceIds"))) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        SubmitJobRequest request;
        try {
            request = mapper.treeToValue(arguments, SubmitJobRequest.class);
        } catch (JsonProcessingException ex) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        if (!validator.validate(request).isEmpty()) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        JobView job = jobs.submit(subject, request, submitTimeout);
        return toolResult(id, write(job), false);
    }

    private ObjectNode getJob(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, JOB_FIELDS) || !textual(arguments, "jobId")) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(arguments.get("jobId").asText());
        } catch (IllegalArgumentException ex) {
            return error(id, INVALID_PARAMS, "invalid params");
        }
        return toolResult(id, write(jobs.getJob(subject, jobId)), false);
    }

    private ObjectNode toolsList() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool(
                "list_devices",
                "List registered devices: device id, hostname, agents, and last heartbeat.",
                mapper.createObjectNode()));
        ObjectNode submit = mapper.createObjectNode();
        submit.set("deviceIds", stringArraySchema(1, 32, 128));
        ObjectNode agentName = mapper.createObjectNode();
        agentName.put("type", "string");
        agentName.put("minLength", 1);
        agentName.put("maxLength", 64);
        submit.set("agentName", agentName);
        ObjectNode prompt = mapper.createObjectNode();
        prompt.put("type", "string");
        prompt.put("minLength", 1);
        prompt.put("maxLength", 16000);
        submit.set("prompt", prompt);
        ObjectNode channel = mapper.createObjectNode();
        channel.put("type", "string");
        ArrayNode channels = mapper.createArrayNode();
        channels.add("TEXT");
        channels.add("SPEECH");
        channel.set("enum", channels);
        submit.set("channel", channel);
        tools.add(tool(
                "submit_job",
                "Queue a prompt on registered devices and wait until every assignment is terminal or the submit timeout elapses.",
                submit,
                "deviceIds",
                "agentName",
                "prompt",
                "channel"));
        ObjectNode job = mapper.createObjectNode();
        ObjectNode jobId = mapper.createObjectNode();
        jobId.put("type", "string");
        jobId.put("format", "uuid");
        job.set("jobId", jobId);
        tools.add(tool("get_job", "Read one job submitted by this caller.", job, "jobId"));
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode tool(String name, String description, ObjectNode properties, String... required) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        if (required.length > 0) {
            ArrayNode requiredFields = mapper.createArrayNode();
            for (String field : required) {
                requiredFields.add(field);
            }
            schema.set("required", requiredFields);
        }
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode stringArraySchema(int minItems, int maxItems, int maxLength) {
        ObjectNode items = mapper.createObjectNode();
        items.put("type", "string");
        items.put("minLength", 1);
        items.put("maxLength", maxLength);
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "array");
        schema.put("minItems", minItems);
        schema.put("maxItems", maxItems);
        schema.set("items", items);
        return schema;
    }

    /**
     * A presented session that is unknown or bound to someone else is rejected
     * before the body is read, so the tool does not run and the prompt is not logged.
     */
    private Response rejectPresentedSession(String sessionId, String subject) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        McpSessions.Status status = sessions.check(sessionId, subject);
        if (status == McpSessions.Status.MATCH) {
            return null;
        }
        return sessionFailure(status, null);
    }

    private Response sessionFailure(McpSessions.Status status, JsonNode id) {
        return switch (status) {
            case ABSENT -> json(400, error(id, INVALID_REQUEST, "session required"), null);
            case UNKNOWN -> json(404, error(id, INVALID_REQUEST, "session not found"), null);
            case OTHER_SUBJECT -> json(403, error(id, INVALID_REQUEST, "session belongs to another subject"), null);
            case MATCH -> throw new IllegalStateException("session matched");
        };
    }

    private String matchedSession(String sessionId, String subject) {
        return sessions.check(sessionId, subject) == McpSessions.Status.MATCH ? sessionId : null;
    }

    private ObjectNode toolResult(JsonNode id, String text, boolean isError) {
        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        ArrayNode contents = mapper.createArrayNode();
        contents.add(content);
        ObjectNode result = mapper.createObjectNode();
        result.set("content", contents);
        result.put("isError", isError);
        return result(id, result);
    }

    private ObjectNode toolError(JsonNode id, String message) {
        return toolResult(id, message, true);
    }

    private ObjectNode result(JsonNode id, ObjectNode value) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null ? mapper.nullNode() : id);
        root.set("result", value);
        return root;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode err = mapper.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.set("id", id == null || id.isNull() ? mapper.nullNode() : id);
        root.set("error", err);
        return root;
    }

    private Response json(int status, ObjectNode body, String sessionId) {
        Response.ResponseBuilder builder = Response.status(status).type(MediaType.APPLICATION_JSON).entity(body);
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return builder.build();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("empty");
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("parse");
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("encode");
        }
    }

    private static boolean onlyFields(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean textual(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual();
    }

    private static boolean stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }
}
