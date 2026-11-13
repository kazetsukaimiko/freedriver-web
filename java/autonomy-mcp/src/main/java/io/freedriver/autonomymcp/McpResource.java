package io.freedriver.autonomymcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.freedriver.autonomymcp.api.Actors;
import io.freedriver.autonomymcp.api.McpApi;
import io.freedriver.autonomymcp.model.JsonRpcResponse;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.McpDebugResponse;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@ApplicationScoped
public class McpResource implements McpApi {

    private static final String LATEST_PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOL_VERSIONS = Set.of("2025-03-26", "2025-06-18", LATEST_PROTOCOL);
    private static final Set<String> SWITCH_REQUIRED = Set.of("instanceId", "applianceName", "on");
    private static final Set<String> SWITCH_OPTIONAL = Set.of("delaySeconds");

    private final SecurityIdentity identity;
    private final McpSessions sessions;
    private final McpSessionHeader sessionHeader;
    private final ObjectMapper mapper;
    private final DashboardCalls dashboard;
    private final ToolRateLimiter limiter;
    private final McpAudit audit;
    @Inject
    public McpResource(
            SecurityIdentity identity,
            McpSessions sessions,
            McpSessionHeader sessionHeader,
            ObjectMapper mapper,
            DashboardCalls dashboard,
            ToolRateLimiter limiter,
            McpAudit audit) {
        this.identity = identity;
        this.sessions = sessions;
        this.sessionHeader = sessionHeader;
        this.mapper = mapper;
        this.dashboard = dashboard;
        this.limiter = limiter;
        this.audit = audit;
    }

    @Override
    @Blocking
    public JsonRpcResponse post(String sessionId, String protocolVersion, String body) {
        String subject = requireSubject();
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
        requireSession(sessionId, requireSubject());
        throw McpProtocolException.notAllowed();
    }

    @Override
    public void delete(String sessionId) {
        String subject = requireSubject();
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
        result.set("capabilities", mapper.createObjectNode().set("tools", tools));
        ObjectNode server = mapper.createObjectNode();
        server.put("name", "freedriver-autonomy");
        server.put("version", "1");
        result.set("serverInfo", server);
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
        return switch (name) {
            case "list_appliances" -> listAppliances(subject, id, arguments);
            case "switch_appliance" -> switchAppliance(subject, id, arguments);
            case "home_status" -> homeStatus(subject, id, arguments);
            case "debug" -> debug(subject, id, arguments);
            case null -> throw McpProtocolException.invalidParams(id, "invalid params");
            default -> throw McpProtocolException.invalidParams(id, "unknown tool");
        };
    }

    private JsonRpcResponse listAppliances(String subject, JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolGet(subject, id, "list_appliances", null, null, dashboard::appliances,
                (ApplianceMapResponse map) -> ApplianceList.json(mapper, map));
    }

    private JsonRpcResponse homeStatus(String subject, JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolGet(subject, id, "home_status", null, null, dashboard::homeStatus,
                (HomeStatusResponse status) -> Spoken.home(status));
    }

    private JsonRpcResponse debug(String subject, JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        if (!identity.hasRole(Actors.MCP_DEBUG)) {
            audit(subject, "debug", null, null, "denied");
            return toolResult(id, "That was refused.", true);
        }
        return toolGet(subject, id, "debug", null, null, dashboard::debug,
                (McpDebugResponse debug) -> Spoken.debug(debug));
    }

    private JsonRpcResponse switchAppliance(String subject, JsonNode id, JsonNode arguments) {
        int delay = delaySeconds(arguments);
        if (!acceptedFields(arguments, SWITCH_REQUIRED, SWITCH_OPTIONAL)
                || !textual(arguments, "instanceId")
                || !textual(arguments, "applianceName")
                || !arguments.has("on")
                || !arguments.get("on").isBoolean()
                || delay < 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        if (!identity.hasRole(Actors.DASHBOARD) && !identity.hasRole(Actors.PORTAL_ADMIN)) {
            audit(subject, "switch_appliance", text(arguments.get("instanceId")), text(arguments.get("applianceName")), "denied");
            return toolResult(id, "That was refused.", true);
        }
        UUID instanceId;
        try {
            instanceId = UUID.fromString(arguments.get("instanceId").asText());
        } catch (IllegalArgumentException ex) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        String applianceName = arguments.get("applianceName").asText();
        boolean on = arguments.get("on").asBoolean();
        if (!limiter.tryAcquire(subject, "switch_appliance")) {
            audit(subject, "switch_appliance", instanceId.toString(), applianceName, "rate-limited");
            return toolResult(id, Spoken.refusal(429), true);
        }
        if (delay > 0) {
            return scheduleSwitch(subject, id, instanceId, applianceName, on, delay);
        }
        return switchNow(subject, id, instanceId, applianceName, on);
    }

    private JsonRpcResponse scheduleSwitch(
            String subject, JsonNode id, UUID instanceId, String applianceName, boolean on, int delay) {
        int status = dashboard.schedule(instanceId, applianceName, on, Instant.now().plusSeconds(delay)).status();
        boolean stored = status >= 200 && status < 300;
        audit(subject, "switch_appliance", instanceId.toString(), applianceName, stored ? "scheduled" : resultOf(status));
        if (stored) {
            return toolResult(id, Spoken.scheduled(applianceName, on, delay), false);
        }
        return toolResult(id, Spoken.refusal(status), true);
    }

    private JsonRpcResponse switchNow(String subject, JsonNode id, UUID instanceId, String applianceName, boolean on) {
        int status = dashboard.command(instanceId, applianceName, on).status();
        audit(subject, "switch_appliance", instanceId.toString(), applianceName, resultOf(status));
        if (status >= 200 && status < 300) {
            return toolResult(id, Spoken.switched(applianceName, on), false);
        }
        return toolResult(id, Spoken.refusal(status), true);
    }

    private <T> JsonRpcResponse toolGet(
            String subject,
            JsonNode id,
            String tool,
            String instance,
            String appliance,
            Supplier<DashboardCalls.Answer<T>> read,
            BodyText<T> bodyText) {
        if (!limiter.tryAcquire(subject, tool)) {
            audit(subject, tool, instance, appliance, "rate-limited");
            return toolResult(id, Spoken.refusal(429), true);
        }
        DashboardCalls.Answer<T> answer = read.get();
        int status = answer.status();
        audit(subject, tool, instance, appliance, resultOf(status));
        if (status >= 200 && status < 300) {
            try {
                return toolResult(id, bodyText.render(answer.body()), false);
            } catch (Exception e) {
                audit(subject, tool, instance, appliance, "error");
                return toolResult(id, Spoken.refusal(500), true);
            }
        }
        return toolResult(id, Spoken.refusal(status), true);
    }

    private ObjectNode toolsList() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool(
                "list_appliances",
                "Return a JSON array. Each object has houseName, instanceId, and appliances. "
                        + "Each appliance has name and state, on or off. "
                        + "Pass instanceId to switch_appliance.",
                mapper.createObjectNode()));
        ObjectNode switchProps = mapper.createObjectNode();
        ObjectNode instanceId = mapper.createObjectNode();
        instanceId.put("type", "string");
        instanceId.put("format", "uuid");
        switchProps.set("instanceId", instanceId);
        ObjectNode applianceName = mapper.createObjectNode();
        applianceName.put("type", "string");
        applianceName.put("minLength", 1);
        applianceName.put("maxLength", 64);
        switchProps.set("applianceName", applianceName);
        ObjectNode on = mapper.createObjectNode();
        on.put("type", "boolean");
        switchProps.set("on", on);
        ObjectNode delay = mapper.createObjectNode();
        delay.put("type", "integer");
        delay.put("minimum", 0);
        delay.put("maximum", SwitchDelay.MAX_DELAY_SECONDS);
        delay.put("description", "Seconds before this switch. Five minutes is 300. The maximum is 86400.");
        switchProps.set("delaySeconds", delay);
        tools.add(tool(
                "switch_appliance",
                "Switch one named appliance on or off. Same command path as the dashboard. "
                        + "delaySeconds waits that many seconds, then switches. "
                        + "Omit it or pass 0 to switch now. "
                        + "To leave the fridge light on for five minutes, switch it on with no delay, "
                        + "then switch it off with delaySeconds 300.",
                switchProps,
                "instanceId",
                "applianceName",
                "on"));
        tools.add(tool("home_status", "Broker connection and last state update.", mapper.createObjectNode()));
        tools.add(tool("debug", "Read-only health and recent error category.", mapper.createObjectNode()));
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

    private String requireSubject() {
        String subject = CallerIds.subject(identity);
        if (subject == null) {
            throw McpProtocolException.invalid(null, "missing subject");
        }
        return subject;
    }

    private void audit(String subject, String tool, String instance, String appliance, String result) {
        audit.record(new McpAudit.Event(
                Instant.now(),
                subject,
                CallerIds.azp(identity),
                tool,
                instance,
                appliance,
                result));
    }

    static String resultOf(int status) {
        if (status == 429) {
            return "rate-limited";
        }
        if (status >= 200 && status < 300) {
            return "allowed";
        }
        if (status >= 500) {
            return "error";
        }
        return "denied";
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
        } catch (JsonProcessingException e) {
            throw McpProtocolException.parse();
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    private static boolean textual(JsonNode object, String field) {
        JsonNode node = object.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private static boolean acceptedFields(JsonNode object, Set<String> required, Set<String> optional) {
        Iterator<String> names = object.fieldNames();
        int requiredSeen = 0;
        while (names.hasNext()) {
            String name = names.next();
            if (required.contains(name)) {
                requiredSeen++;
            } else if (!optional.contains(name)) {
                return false;
            }
        }
        return requiredSeen == required.size();
    }

    /** Seconds to wait, 0 when the field is omitted, or -1 when the field is unusable. */
    private static int delaySeconds(JsonNode arguments) {
        if (!arguments.has("delaySeconds")) {
            return 0;
        }
        JsonNode node = arguments.get("delaySeconds");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            return -1;
        }
        int value = node.intValue();
        if (value < 0 || value > SwitchDelay.MAX_DELAY_SECONDS) {
            return -1;
        }
        return value;
    }

    @FunctionalInterface
    private interface BodyText<T> {
        String render(T body) throws Exception;
    }
}
