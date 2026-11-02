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

@ApplicationScoped
public class McpResource implements McpApi {

    private static final String LATEST_PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOL_VERSIONS = Set.of("2025-03-26", "2025-06-18", LATEST_PROTOCOL);
    private static final Set<String> SWITCH_FIELDS = Set.of("instanceId", "applianceName", "on");

    private final SecurityIdentity identity;
    private final McpSessions sessions;
    private final McpSessionHeader sessionHeader;
    private final ObjectMapper mapper;
    private final DashboardClient dashboard;
    private final ToolRateLimiter limiter;
    private final McpAudit audit;

    @Inject
    public McpResource(
            SecurityIdentity identity,
            McpSessions sessions,
            McpSessionHeader sessionHeader,
            ObjectMapper mapper,
            DashboardClient dashboard,
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
        return toolGet(subject, id, "list_appliances", "/api/mcp/appliances", null, null, body ->
                Spoken.list(mapper.readValue(body, ApplianceMapResponse.class)));
    }

    private JsonRpcResponse homeStatus(String subject, JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        return toolGet(subject, id, "home_status", "/api/mcp/home-status", null, null, body ->
                Spoken.home(mapper.readValue(body, HomeStatusResponse.class)));
    }

    private JsonRpcResponse debug(String subject, JsonNode id, JsonNode arguments) {
        if (arguments.size() != 0) {
            throw McpProtocolException.invalidParams(id, "invalid params");
        }
        if (!identity.hasRole(Actors.MCP_DEBUG)) {
            audit(subject, "debug", null, null, "denied");
            return toolResult(id, "That was refused.", true);
        }
        return toolGet(subject, id, "debug", "/api/mcp/debug", null, null, body ->
                Spoken.debug(mapper.readValue(body, McpDebugResponse.class)));
    }

    private JsonRpcResponse switchAppliance(String subject, JsonNode id, JsonNode arguments) {
        if (!onlyFields(arguments, SWITCH_FIELDS)
                || !textual(arguments, "instanceId")
                || !textual(arguments, "applianceName")
                || !arguments.has("on")
                || !arguments.get("on").isBoolean()) {
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
        String json = "{\"on\":" + on + "}";
        DashboardClient.Call call = dashboard.post(
                "/api/appliances/" + instanceId + "/" + applianceName,
                json,
                subject,
                CallerIds.roles(identity));
        String result = resultOf(call.status());
        audit(subject, "switch_appliance", instanceId.toString(), applianceName, result);
        if (call.status() >= 200 && call.status() < 300) {
            return toolResult(id, Spoken.switched(applianceName, on), false);
        }
        return toolResult(id, Spoken.refusal(call.status()), true);
    }

    private JsonRpcResponse toolGet(
            String subject,
            JsonNode id,
            String tool,
            String path,
            String instance,
            String appliance,
            BodyText bodyText) {
        if (!limiter.tryAcquire(subject, tool)) {
            audit(subject, tool, instance, appliance, "rate-limited");
            return toolResult(id, Spoken.refusal(429), true);
        }
        DashboardClient.Call call = dashboard.get(path, subject, CallerIds.roles(identity));
        String result = resultOf(call.status());
        audit(subject, tool, instance, appliance, result);
        if (call.status() >= 200 && call.status() < 300) {
            try {
                return toolResult(id, bodyText.render(call.body()), false);
            } catch (Exception e) {
                audit(subject, tool, instance, appliance, "error");
                return toolResult(id, Spoken.refusal(500), true);
            }
        }
        return toolResult(id, Spoken.refusal(call.status()), true);
    }

    private ObjectNode toolsList() {
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("list_appliances", "List appliances and whether each is on.", mapper.createObjectNode()));
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
        tools.add(tool(
                "switch_appliance",
                "Switch one named appliance on or off. Same command path as the dashboard.",
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

    private static String resultOf(int status) {
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

    private static boolean onlyFields(JsonNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        int count = 0;
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
            count++;
        }
        return count == allowed.size();
    }

    @FunctionalInterface
    private interface BodyText {
        String render(String body) throws Exception;
    }
}
