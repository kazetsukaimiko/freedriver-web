package io.freedriver.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.dispatcher.api.McpApi;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.JsonRpcResponse;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
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
@ApplicationScoped
public class McpResource implements McpApi {

    private static final String LATEST_PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOL_VERSIONS = Set.of("2025-03-26", "2025-06-18", LATEST_PROTOCOL);
    private static final Set<String> SUBMIT_FIELDS = Set.of("deviceIds", "agentName", "prompt", "channel");
    private static final Set<String> JOB_FIELDS = Set.of("jobId");

    private final JobStore jobs;
    private final McpSessions sessions;
    private final McpSessionHeader sessionHeader;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final Duration submitTimeout;

    @Inject
    public McpResource(
            JobStore jobs,
            McpSessions sessions,
            McpSessionHeader sessionHeader,
            SecurityIdentity identity,
            ObjectMapper mapper,
            Validator validator,
            @ConfigProperty(name = "freedriver.dispatcher.submit-timeout", defaultValue = "75s") Duration submitTimeout) {
        this.jobs = jobs;
        this.sessions = sessions;
        this.sessionHeader = sessionHeader;
        this.identity = identity;
        this.mapper = mapper;
        this.validator = validator;
        this.submitTimeout = submitTimeout;
    }

    @Override
    @Blocking
    public JsonRpcResponse post(String sessionId, String protocolVersion, String body) {
        String subject = CallerIds.subject(identity);
        rejectPresentedSession(sessionId, subject);
        JsonNode message = parse(body);
        if (!message.isObject()) {
            throw McpProtocolException.invalid(null, "invalid request");
        }
        JsonNode id = message.get("id");
        if (protocolVersion != null && !protocolVersion.isBlank() && !PROTOCOL_VERSIONS.contains(protocolVersion)) {
            attachMatched(sessionId, subject);
            throw McpProtocolException.invalid(id, "unsupported protocol version");
        }
        String method = text(message.get("method"));
        if (method == null) {
            throw McpProtocolException.invalid(id, "invalid request");
        }
        if ("initialize".equals(method)) {
            return initialize(subject, id, message.get("params"));
        }
        McpSessions.Status status = sessions.check(sessionId, subject);
        if (status != McpSessions.Status.MATCH) {
            throw sessionFailure(status, id);
        }
        sessionHeader.set(sessionId);
        if (!message.has("id")) {
            throw McpProtocolException.accepted();
        }
        return switch (method) {
            case "ping" -> envelope(id, mapper.createObjectNode());
            case "tools/list" -> envelope(id, toolsList());
            case "tools/call" -> callTool(subject, id, message.get("params"));
            default -> throw McpProtocolException.methodNotFound(id);
        };
    }

    @Override
    public void get(String sessionId) {
        requireSession(sessionId, CallerIds.subject(identity));
        throw McpProtocolException.notAllowed();
    }

    @Override
    public void delete(String sessionId) {
        String subject = CallerIds.subject(identity);
        requireSession(sessionId, subject);
        sessions.close(sessionId, subject);
    }

    private JsonRpcResponse initialize(String subject, JsonNode id, JsonNode params) {
        String requested = params != null && params.isObject() ? text(params.get("protocolVersion")) : null;
        String version = requested != null && PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_PROTOCOL;
        sessionHeader.set(sessions.open(subject));
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
        return envelope(id, result);
    }

    private JsonRpcResponse callTool(String subject, JsonNode id, JsonNode params) {
        if (params == null || !params.isObject()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        String name = text(params.get("name"));
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = mapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        try {
            return switch (name) {
                case "list_devices" -> listDevices(id, arguments);
                case "submit_job" -> submitJob(subject, id, arguments);
                case "get_job" -> getJob(subject, id, arguments);
                case null -> throw McpProtocolException.invalidParams(id, "invalid params");
                default -> throw McpProtocolException.invalidParams(id, "unknown tool");
            };
        } catch (DispatcherException ex) {
            String message = ex.getMessage();
            return toolResult(id, message == null || message.isBlank() ? "request failed" : message, true);
        }
    }

    private JsonRpcResponse listDevices(JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolResult(id, write(jobs.listDevices()), false);
    }

    private JsonRpcResponse submitJob(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, SUBMIT_FIELDS)
                || !textual(arguments, "agentName")
                || !textual(arguments, "prompt")
                || !textual(arguments, "channel")
                || !stringArray(arguments.get("deviceIds"))) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        SubmitJobRequest request;
        try {
            request = mapper.treeToValue(arguments, SubmitJobRequest.class);
        } catch (JsonProcessingException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        if (!validator.validate(request).isEmpty()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        JobView job = jobs.submit(subject, request, submitTimeout);
        return toolResult(id, write(job), false);
    }

    private JsonRpcResponse getJob(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, JOB_FIELDS) || !textual(arguments, "jobId")) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(arguments.get("jobId").asText());
        } catch (IllegalArgumentException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
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
     * before the body is parsed, so the tool does not run and the prompt is not logged.
     */
    private void rejectPresentedSession(String sessionId, String subject) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        McpSessions.Status status = sessions.check(sessionId, subject);
        if (status != McpSessions.Status.MATCH) {
            throw sessionFailure(status, null);
        }
    }

    private void requireSession(String sessionId, String subject) {
        McpSessions.Status status = sessions.check(sessionId, subject);
        if (status != McpSessions.Status.MATCH) {
            throw sessionFailure(status, null);
        }
    }

    private McpProtocolException sessionFailure(McpSessions.Status status, JsonNode id) {
        return switch (status) {
            case ABSENT -> McpProtocolException.invalid(id, "session required");
            case UNKNOWN -> McpProtocolException.notFound(id);
            case OTHER_SUBJECT -> McpProtocolException.forbidden(id);
            case MATCH -> throw new IllegalStateException("session matched");
        };
    }

    private void attachMatched(String sessionId, String subject) {
        if (sessions.check(sessionId, subject) == McpSessions.Status.MATCH) {
            sessionHeader.set(sessionId);
        }
    }

    private JsonRpcResponse toolResult(JsonNode id, String text, boolean isError) {
        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        ArrayNode contents = mapper.createArrayNode();
        contents.add(content);
        ObjectNode result = mapper.createObjectNode();
        result.set("content", contents);
        result.put("isError", isError);
        return envelope(id, result);
    }

    private JsonRpcResponse envelope(JsonNode id, ObjectNode value) {
        return new JsonRpcResponse("2.0", id == null || id.isNull() ? null : id, value);
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw McpProtocolException.parse();
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException ex) {
            throw McpProtocolException.parse();
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
