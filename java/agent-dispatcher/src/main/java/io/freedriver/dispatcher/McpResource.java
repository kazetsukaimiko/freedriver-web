package io.freedriver.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.dispatcher.api.McpApi;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.JobView;
import io.freedriver.dispatcher.model.JsonRpcResponse;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
    private static final int AWAIT_MAX_SECONDS = 120;
    private static final Set<String> SEND_FIELDS = Set.of(
            "server", "agentName", "prompt", "channel", "on_busy");
    private static final Set<String> AWAIT_FIELDS = Set.of("jobId", "delaySeconds");
    private static final Set<String> JOB_FIELDS = Set.of("jobId");

    private final JobStore jobs;
    private final McpSessions sessions;
    private final McpSessionHeader sessionHeader;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final Validator validator;

    @Inject
    public McpResource(
            JobStore jobs,
            McpSessions sessions,
            McpSessionHeader sessionHeader,
            SecurityIdentity identity,
            ObjectMapper mapper,
            Validator validator) {
        this.jobs = jobs;
        this.sessions = sessions;
        this.sessionHeader = sessionHeader;
        this.identity = identity;
        this.mapper = mapper;
        this.validator = validator;
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
        if (status == McpSessions.Status.MATCH) {
            sessionHeader.set(sessionId);
        } else if (!(status == McpSessions.Status.ABSENT && sessionOptional(method))) {
            throw sessionFailure(status, id);
        }
        if (!message.has("id")) {
            throw McpProtocolException.accepted();
        }
        return switch (method) {
            case "ping" -> envelope(id, mapper.createObjectNode());
            case "tools/list" -> envelope(id, toolsList(identity));
            case "tools/call" -> callTool(identity, id, message.get("params"));
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

    private JsonRpcResponse callTool(SecurityIdentity caller, JsonNode id, JsonNode params) {
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
        String subject = CallerIds.subject(caller);
        boolean deviceRole = caller.hasRole("device");
        boolean dispatcherRole = caller.hasRole("dispatcher");
        boolean hostTool = "report_host".equals(name) || "finish_job".equals(name);
        boolean callerTool = "list_agents".equals(name)
                || "openbot_send_prompt".equals(name)
                || "openbot_await_turn".equals(name)
                || "get_job".equals(name);
        if (hostTool && !deviceRole) {
            return toolResult(id, "wrong role", true);
        }
        if (callerTool && !dispatcherRole) {
            return toolResult(id, "wrong role", true);
        }
        try {
            return switch (name) {
                case "list_agents" -> listAgents(id, arguments);
                case "openbot_send_prompt" -> sendPrompt(subject, id, arguments);
                case "openbot_await_turn" -> awaitTurn(subject, id, arguments);
                case "get_job" -> getJob(subject, id, arguments);
                case "report_host" -> reportHost(caller, id, arguments);
                case "finish_job" -> finishJob(caller, id, arguments);
                case null -> throw McpProtocolException.invalidParams(id, "invalid params");
                default -> throw McpProtocolException.invalidParams(id, "unknown tool");
            };
        } catch (DispatcherException ex) {
            String message = ex.getMessage();
            return toolResult(id, message == null || message.isBlank() ? "request failed" : message, true);
        }
    }

    private JsonRpcResponse listAgents(JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolResult(id, write(jobs.listAgents()), false);
    }

    private JsonRpcResponse sendPrompt(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, SEND_FIELDS)
                || !textual(arguments, "server")
                || !textual(arguments, "agentName")
                || !textual(arguments, "prompt")
                || !textual(arguments, "channel")) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        String server = arguments.get("server").asText();
        String agentName = arguments.get("agentName").asText();
        String prompt = arguments.get("prompt").asText();
        String channelName = arguments.get("channel").asText();
        if (server.length() > 253 || agentName.length() > 64 || prompt.isEmpty() || prompt.length() > 16000) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        Channel channel;
        try {
            channel = Channel.valueOf(channelName);
        } catch (IllegalArgumentException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        OnBusy onBusy = onBusy(id, arguments.get("on_busy"));
        String deviceId = jobs.deviceIdFor(server, agentName);
        SubmitJobRequest request = new SubmitJobRequest(List.of(deviceId), agentName, prompt, channel, onBusy);
        if (!validator.validate(request).isEmpty()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolResult(id, write(jobs.submitNow(subject, request)), false);
    }

    private JsonRpcResponse awaitTurn(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, AWAIT_FIELDS)
                || !textual(arguments, "jobId")
                || arguments.get("delaySeconds") == null
                || !arguments.get("delaySeconds").isIntegralNumber()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        int delay = arguments.get("delaySeconds").asInt();
        if (delay < 0 || delay > AWAIT_MAX_SECONDS || !arguments.get("delaySeconds").canConvertToInt()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(arguments.get("jobId").asText());
        } catch (IllegalArgumentException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolResult(id, write(jobs.awaitTurn(subject, jobId, Duration.ofSeconds(delay))), false);
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

    private JsonRpcResponse reportHost(SecurityIdentity caller, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, Set.of("hostname", "bots"))
                || !textual(arguments, "hostname")
                || arguments.get("bots") == null
                || !arguments.get("bots").isArray()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        List<ReportedBot> bots = new ArrayList<>();
        for (JsonNode bot : arguments.get("bots")) {
            if (bot == null || !bot.isObject() || !onlyFields(bot, Set.of("name", "state", "activity"))
                    || !textual(bot, "name") || !textual(bot, "state")
                    || (bot.has("activity") && !textual(bot, "activity"))) {
                throw McpProtocolException.invalidParams(id, "invalid params");
            }
            String activity = bot.has("activity") ? bot.get("activity").asText() : "";
            bots.add(new ReportedBot(bot.get("name").asText(), bot.get("state").asText(), activity));
        }
        String deviceId = CallerIds.deviceId(caller);
        ObjectNode body = mapper.createObjectNode();
        body.set("host", mapper.valueToTree(jobs.report(deviceId, arguments.get("hostname").asText(), bots)));
        body.set("jobs", mapper.valueToTree(jobs.poll(deviceId, Duration.ZERO)));
        return toolResult(id, write(body), false);
    }

    private JsonRpcResponse finishJob(SecurityIdentity caller, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, Set.of("assignmentId", "succeeded", "note"))
                || !textual(arguments, "assignmentId")
                || arguments.get("succeeded") == null
                || !arguments.get("succeeded").isBoolean()
                || (arguments.has("note") && !textual(arguments, "note"))) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        UUID assignmentId;
        try {
            assignmentId = UUID.fromString(arguments.get("assignmentId").asText());
        } catch (IllegalArgumentException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        String note = arguments.has("note") ? arguments.get("note").asText() : null;
        return toolResult(id, write(jobs.finish(
                CallerIds.deviceId(caller),
                assignmentId,
                arguments.get("succeeded").asBoolean(),
                note)), false);
    }

    private ObjectNode toolsList(SecurityIdentity caller) {
        ArrayNode tools = mapper.createArrayNode();
        boolean deviceRole = caller.hasRole("device");
        boolean dispatcherRole = caller.hasRole("dispatcher");
        if (dispatcherRole || !deviceRole) {
        tools.add(tool(
                "list_agents",
                "Freedriver dispatch: list agents that can take a job, with name, server, idle or busy state, and activity.",
                mapper.createObjectNode()));
        ObjectNode submit = mapper.createObjectNode();
        ObjectNode server = mapper.createObjectNode();
        server.put("type", "string");
        server.put("minLength", 1);
        server.put("maxLength", 253);
        submit.set("server", server);
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
        ObjectNode onBusy = mapper.createObjectNode();
        ArrayNode onBusyTypes = mapper.createArrayNode();
        onBusyTypes.add("string");
        onBusyTypes.add("null");
        onBusy.set("type", onBusyTypes);
        ArrayNode onBusyValues = mapper.createArrayNode();
        onBusyValues.add("supersede");
        onBusyValues.add("queue");
        onBusyValues.addNull();
        onBusy.set("enum", onBusyValues);
        submit.set("on_busy", onBusy);
        tools.add(tool(
                "openbot_send_prompt",
                "Freedriver dispatch: queue a prompt for a named agent on one server from list_agents, such as Project Manager. "
                        + "Returns the job id as soon as the job is stored. It does not wait for OpenBot. "
                        + "on_busy is supersede or queue. Omit it or pass null for supersede. "
                        + "The value is stored on the job and returned with queued jobs. "
                        + "openbot_await_turn waits for that id.",
                submit,
                "server",
                "agentName",
                "prompt",
                "channel"));
        ObjectNode await = mapper.createObjectNode();
        await.set("jobId", jobIdNode());
        ObjectNode delaySeconds = mapper.createObjectNode();
        delaySeconds.put("type", "integer");
        delaySeconds.put("minimum", 0);
        delaySeconds.put("maximum", AWAIT_MAX_SECONDS);
        await.set("delaySeconds", delaySeconds);
        tools.add(tool(
                "openbot_await_turn",
                "Freedriver dispatch: wait until the job is done, or until delaySeconds elapses. "
                        + "The longest delay is 120 seconds. A timeout leaves the job as it is.",
                await,
                "jobId",
                "delaySeconds"));
        ObjectNode job = mapper.createObjectNode();
        job.set("jobId", jobIdNode());
        tools.add(tool("get_job", "Freedriver dispatch: read one job this caller submitted, including its id and result.", job, "jobId"));
        }
        if (deviceRole) {
            ObjectNode hostname = mapper.createObjectNode();
            hostname.put("type", "string");
            hostname.put("minLength", 1);
            hostname.put("maxLength", 253);
            ObjectNode botName = mapper.createObjectNode();
            botName.put("type", "string");
            botName.put("minLength", 1);
            botName.put("maxLength", 64);
            ObjectNode state = mapper.createObjectNode();
            state.put("type", "string");
            ArrayNode states = mapper.createArrayNode();
            states.add("idle");
            states.add("busy");
            state.set("enum", states);
            ObjectNode activity = mapper.createObjectNode();
            activity.put("type", "string");
            activity.put("maxLength", 500);
            ObjectNode botProps = mapper.createObjectNode();
            botProps.set("name", botName);
            botProps.set("state", state);
            botProps.set("activity", activity);
            ObjectNode bot = mapper.createObjectNode();
            bot.put("type", "object");
            bot.put("additionalProperties", false);
            bot.set("properties", botProps);
            ArrayNode botRequired = mapper.createArrayNode();
            botRequired.add("name");
            botRequired.add("state");
            bot.set("required", botRequired);
            ObjectNode bots = mapper.createObjectNode();
            bots.put("type", "array");
            bots.put("minItems", 1);
            bots.put("maxItems", 32);
            bots.set("items", bot);
            ObjectNode report = mapper.createObjectNode();
            report.set("hostname", hostname);
            report.set("bots", bots);
            tools.add(tool(
                    "report_host",
                    "Freedriver dispatch: publish this host's bots, each idle or busy, with a short activity note. "
                            + "Returns this host's jobs whose status is QUEUED, including each job's on_busy.",
                    report,
                    "hostname",
                    "bots"));
            ObjectNode finish = mapper.createObjectNode();
            ObjectNode assignmentId = mapper.createObjectNode();
            assignmentId.put("type", "string");
            assignmentId.put("format", "uuid");
            finish.set("assignmentId", assignmentId);
            ObjectNode succeeded = mapper.createObjectNode();
            succeeded.put("type", "boolean");
            finish.set("succeeded", succeeded);
            ObjectNode note = mapper.createObjectNode();
            note.put("type", "string");
            note.put("maxLength", 2000);
            finish.set("note", note);
            tools.add(tool(
                    "finish_job",
                    "Freedriver dispatch: post the result of one queued job so the caller can read it.",
                    finish,
                    "assignmentId",
                    "succeeded"));
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private static OnBusy onBusy(JsonNode id, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return OnBusy.supersede;
        }
        if (!value.isTextual()) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        try {
            return OnBusy.valueOf(value.asText());
        } catch (IllegalArgumentException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
    }

    private ObjectNode jobIdNode() {
        ObjectNode jobId = mapper.createObjectNode();
        jobId.put("type", "string");
        jobId.put("format", "uuid");
        return jobId;
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

    /** A missing session is not a credential. Listing tools and ping still answer. */
    private static boolean sessionOptional(String method) {
        return "tools/list".equals(method) || "ping".equals(method);
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
