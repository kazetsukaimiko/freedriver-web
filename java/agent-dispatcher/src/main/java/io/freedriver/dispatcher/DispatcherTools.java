package io.freedriver.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dispatcher tools on the Quarkus MCP server at {@code /mcp} and {@code /dispatch/mcp}.
 * {@code openbot_send_prompt} stores the job and returns its id.
 * {@code openbot_await_turn} waits on that id for at most two minutes.
 */
@ApplicationScoped
@McpServer(McpServer.DEFAULT)
@McpServer("dispatch")
public class DispatcherTools {

    private static final int AWAIT_MAX_SECONDS = 120;
    private static final Set<String> SEND_FIELDS = Set.of(
            "server", "agentName", "prompt", "channel", "on_busy");
    private static final Set<String> AWAIT_FIELDS = Set.of("jobId", "delaySeconds");
    private static final Set<String> JOB_FIELDS = Set.of("jobId");

    private final JobStore jobs;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final Validator validator;

    @Inject
    public DispatcherTools(JobStore jobs, SecurityIdentity identity, ObjectMapper mapper, Validator validator) {
        this.jobs = jobs;
        this.identity = identity;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Tool(
            name = "list_agents",
            description = "Freedriver dispatch: list agents that can take a job, with name, server, idle or busy state, and activity.",
            inputSchema = @Tool.InputSchema(generator = DispatcherToolSchemas.class))
    String listAgents(RawMessage raw) {
        require("dispatcher");
        only(raw, Set.of());
        return guard(jobs::listAgents);
    }

    @Tool(
            name = "openbot_send_prompt",
            description = "Freedriver dispatch: queue a prompt for a named agent on one server from list_agents, such as Project Manager. "
                    + "Returns the job id as soon as the job is stored. It does not wait for OpenBot. "
                    + "on_busy is supersede or queue. Omit it or pass null for supersede. "
                    + "The value is stored on the job and returned with queued jobs. "
                    + "openbot_await_turn waits for that id.",
            inputSchema = @Tool.InputSchema(generator = DispatcherToolSchemas.class))
    String openbotSendPrompt(
            RawMessage raw,
            @ToolArg(name = "server") String server,
            @ToolArg(name = "agentName") String agentName,
            @ToolArg(name = "prompt") String prompt,
            @ToolArg(name = "channel") Channel channel,
            @ToolArg(name = "on_busy", required = false) OnBusy onBusy) {
        require("dispatcher");
        only(raw, SEND_FIELDS);
        if (!onBusyText(raw)) {
            throw invalid();
        }
        if (server == null || server.isBlank() || server.length() > 253
                || agentName == null || agentName.isBlank() || agentName.length() > 64
                || prompt == null || prompt.isBlank() || prompt.length() > 16000
                || channel == null) {
            throw invalid();
        }
        OnBusy stored = onBusy == null ? OnBusy.supersede : onBusy;
        return guard(() -> {
            SubmitJobRequest request = new SubmitJobRequest(
                    List.of(jobs.deviceIdFor(server, agentName)), agentName, prompt, channel, stored);
            if (!validator.validate(request).isEmpty()) {
                throw invalid();
            }
            return jobs.submitNow(CallerIds.subject(identity), request);
        });
    }

    @Tool(
            name = "openbot_await_turn",
            description = "Freedriver dispatch: wait until the job is done, or until delaySeconds elapses. "
                    + "The longest delay is 120 seconds. A timeout leaves the job as it is.",
            inputSchema = @Tool.InputSchema(generator = DispatcherToolSchemas.class))
    String openbotAwaitTurn(
            RawMessage raw,
            @ToolArg(name = "jobId") UUID jobId,
            @ToolArg(name = "delaySeconds") int delaySeconds) {
        require("dispatcher");
        only(raw, AWAIT_FIELDS);
        if (delaySeconds < 0 || delaySeconds > AWAIT_MAX_SECONDS || !integral(raw, "delaySeconds")) {
            throw invalid();
        }
        return guard(() -> jobs.awaitTurn(CallerIds.subject(identity), jobId, Duration.ofSeconds(delaySeconds)));
    }

    @Tool(
            name = "get_job",
            description = "Freedriver dispatch: read one job this caller submitted, including its id and result.",
            inputSchema = @Tool.InputSchema(generator = DispatcherToolSchemas.class))
    String getJob(RawMessage raw, @ToolArg(name = "jobId") UUID jobId) {
        require("dispatcher");
        only(raw, JOB_FIELDS);
        return guard(() -> jobs.getJob(CallerIds.subject(identity), jobId));
    }

    @Tool(
            name = "report_host",
            description = "Freedriver dispatch: publish this host's bots, each idle or busy, with a short activity note. "
                    + "Returns this host's jobs whose status is QUEUED, including each job's on_busy.",
            inputSchema = @Tool.InputSchema(generator = DispatcherToolSchemas.class))
    String reportHost(
            RawMessage raw,
            @ToolArg(name = "hostname") String hostname,
            @ToolArg(name = "bots") List<ReportedBot> bots) {
        require("device");
        only(raw, Set.of("hostname", "bots"));
        List<ReportedBot> published = bots(raw);
        if (hostname == null || hostname.isBlank() || hostname.length() > 253 || published == null) {
            throw invalid();
        }
        return guard(() -> {
            String deviceId = CallerIds.deviceId(identity);
            var body = mapper.createObjectNode();
            body.set("host", mapper.valueToTree(jobs.report(deviceId, hostname, published)));
            body.set("jobs", mapper.valueToTree(jobs.poll(deviceId, Duration.ZERO)));
            return body;
        });
    }

    @Tool(
            name = "finish_job",
            description = "Freedriver dispatch: post the result of one queued job so the caller can read it.",
            inputSchema = @Tool.InputSchema(generator = DispatcherToolSchemas.class))
    String finishJob(
            RawMessage raw,
            @ToolArg(name = "assignmentId") UUID assignmentId,
            @ToolArg(name = "succeeded") boolean succeeded,
            @ToolArg(name = "note", required = false) String note) {
        require("device");
        only(raw, Set.of("assignmentId", "succeeded", "note"));
        if (note != null && note.length() > 2000) {
            throw invalid();
        }
        return guard(() -> jobs.finish(CallerIds.deviceId(identity), assignmentId, succeeded, note));
    }

    private void require(String role) {
        if (!identity.hasRole(role)) {
            throw new ToolCallException("wrong role");
        }
    }

    private static void only(RawMessage raw, Set<String> fields) {
        JsonObject arguments = arguments(raw);
        if (arguments == null) {
            if (!fields.isEmpty()) {
                throw invalid();
            }
            return;
        }
        for (String name : arguments.fieldNames()) {
            if (!fields.contains(name)) {
                throw invalid();
            }
        }
    }

    private static boolean onBusyText(RawMessage raw) {
        JsonObject arguments = arguments(raw);
        if (arguments == null || !arguments.containsKey("on_busy") || arguments.getValue("on_busy") == null) {
            return true;
        }
        return arguments.getValue("on_busy") instanceof String;
    }

    private static boolean integral(RawMessage raw, String name) {
        JsonObject arguments = arguments(raw);
        Object value = arguments == null ? null : arguments.getValue(name);
        if (!(value instanceof Number number)) {
            return false;
        }
        double wide = number.doubleValue();
        return wide == Math.rint(wide) && wide >= Integer.MIN_VALUE && wide <= Integer.MAX_VALUE;
    }

    private static List<ReportedBot> bots(RawMessage raw) {
        JsonObject arguments = arguments(raw);
        if (arguments == null || !(arguments.getValue("bots") instanceof JsonArray rows) || rows.isEmpty() || rows.size() > 32) {
            return null;
        }
        List<ReportedBot> bots = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.getValue(i) instanceof JsonObject bot)) {
                return null;
            }
            for (String name : bot.fieldNames()) {
                if (!name.equals("name") && !name.equals("state") && !name.equals("activity")) {
                    return null;
                }
            }
            if (!(bot.getValue("name") instanceof String botName) || botName.isBlank() || botName.length() > 64) {
                return null;
            }
            Object state = bot.getValue("state");
            if (!"idle".equals(state) && !"busy".equals(state)) {
                return null;
            }
            String activity = "";
            if (bot.containsKey("activity")) {
                if (!(bot.getValue("activity") instanceof String text) || text.length() > 500) {
                    return null;
                }
                activity = text;
            }
            bots.add(new ReportedBot(botName, (String) state, activity));
        }
        return bots;
    }

    private static JsonObject arguments(RawMessage raw) {
        JsonObject message = raw.asJsonObject();
        JsonObject params = message.getJsonObject("params");
        if (params == null) {
            return null;
        }
        return params.getJsonObject("arguments");
    }

    private String guard(java.util.function.Supplier<Object> action) {
        try {
            return write(action.get());
        } catch (DispatcherException ex) {
            String message = ex.getMessage();
            throw new ToolCallException(message == null || message.isBlank() ? "request failed" : message);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ToolCallException("request failed");
        }
    }

    private static McpException invalid() {
        return new McpException("invalid params", JsonRpcErrorCodes.INVALID_PARAMS);
    }
}
