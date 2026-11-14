package io.freedriver.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.dispatcher.api.Actors;
import io.freedriver.dispatcher.model.Channel;
import io.freedriver.dispatcher.model.OnBusy;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.SubmitJobRequest;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Dispatcher tools on the Quarkus MCP server at {@code /mcp} and {@code /dispatch/mcp}.
 * The constraints on each argument are the validation and the advertised input schema, and
 * {@code @RolesAllowed} is the role check. {@code openbot_send_prompt} stores the job and returns
 * its id; {@code openbot_await_turn} waits on that id for at most two minutes.
 */
@ApplicationScoped
@McpServer(McpServer.DEFAULT)
@McpServer("dispatch")
public class DispatcherTools {

    private static final int PAGE_DEFAULT = 20;

    private final JobStore jobs;
    private final SecurityIdentity identity;
    private final ObjectMapper mapper;

    @Inject
    public DispatcherTools(JobStore jobs, SecurityIdentity identity, ObjectMapper mapper) {
        this.jobs = jobs;
        this.identity = identity;
        this.mapper = mapper;
    }

    @Tool(
            name = "list_agents",
            description = "Freedriver dispatch: list agents that can take a job, with name, server, idle or busy state, and activity.")
    @RolesAllowed(Actors.DISPATCHER)
    String listAgents() {
        return guard(jobs::listAgents);
    }

    @Tool(
            name = "openbot_send_prompt",
            description = "Freedriver dispatch: queue a prompt for a named agent on one server from list_agents, such as Project Manager. "
                    + "Returns the job id as soon as the job is stored. It does not wait for OpenBot. "
                    + "on_busy is supersede or queue. Omit it or pass null for supersede. "
                    + "The value is stored on the job and returned with queued jobs. "
                    + "openbot_await_turn waits for that id.")
    @RolesAllowed(Actors.DISPATCHER)
    String openbotSendPrompt(
            @ToolArg(name = "server") @NotBlank @Size(max = 253) String server,
            @ToolArg(name = "agentName") @NotBlank @Size(max = 64) String agentName,
            @ToolArg(name = "prompt") @NotBlank @Size(max = 16000) String prompt,
            @ToolArg(name = "channel") @NotNull Channel channel,
            @ToolArg(name = "on_busy", required = false) OnBusy onBusy) {
        OnBusy stored = onBusy == null ? OnBusy.supersede : onBusy;
        return guard(() -> jobs.submitNow(JobCallers.subject(identity), new SubmitJobRequest(
                List.of(jobs.deviceIdFor(server, agentName)), agentName, prompt, channel, stored)));
    }

    @Tool(
            name = "openbot_await_turn",
            description = "Freedriver dispatch: wait until the job is done, or until delaySeconds elapses. "
                    + "The longest delay is 120 seconds. A timeout leaves the job as it is.")
    @RolesAllowed(Actors.DISPATCHER)
    @Blocking
    String openbotAwaitTurn(
            @ToolArg(name = "jobId") @NotNull UUID jobId,
            @ToolArg(name = "delaySeconds") @Min(0) @Max(120) int delaySeconds) {
        return guard(() -> jobs.awaitTurn(JobCallers.subject(identity), jobId, Duration.ofSeconds(delaySeconds)));
    }

    @Tool(
            name = "list_jobs",
            description = "Freedriver dispatch: list jobs this caller submitted, newest first. "
                    + "Each row has the job id, agent name, server, state, submitted time, and a short result note. "
                    + "state is queued, in-progress, or done. agentName and state narrow the list. "
                    + "limit is 1 to 50 and defaults to 20. Pass nextCursor back as cursor for the next page. "
                    + "The prompt is not included.")
    @RolesAllowed(Actors.DISPATCHER)
    String listJobs(
            @ToolArg(name = "agentName", required = false) @Size(min = 1, max = 64) @Pattern(regexp = ".*\\S.*") String agentName,
            @ToolArg(name = "state", required = false) @Pattern(regexp = "queued|in-progress|done") String state,
            @ToolArg(name = "limit", required = false) @Min(1) @Max(50) Integer limit,
            @ToolArg(name = "cursor", required = false) @Pattern(regexp = "[0-9]+/[0-9a-fA-F-]{36}") String cursor) {
        int size = limit == null ? PAGE_DEFAULT : limit;
        return guard(() -> jobs.listJobPage(JobCallers.subject(identity), agentName, state, size, cursor));
    }

    @Tool(
            name = "get_job",
            description = "Freedriver dispatch: read one job this caller submitted, including its id and result.")
    @RolesAllowed(Actors.DISPATCHER)
    String getJob(@ToolArg(name = "jobId") @NotNull UUID jobId) {
        return guard(() -> jobs.getJob(JobCallers.subject(identity), jobId));
    }

    @Tool(
            name = "report_host",
            description = "Freedriver dispatch: publish this host's bots, each idle or busy, with a short activity note. "
                    + "Returns this host's jobs whose status is QUEUED, including each job's on_busy.")
    @RolesAllowed(Actors.DEVICE)
    String reportHost(
            @ToolArg(name = "hostname") @NotBlank @Size(max = 253) String hostname,
            @ToolArg(name = "bots") @NotNull @Size(min = 1, max = 32) List<@Valid @NotNull ReportedBot> bots) {
        return guard(() -> {
            String deviceId = JobCallers.deviceId(identity);
            var body = mapper.createObjectNode();
            body.set("host", mapper.valueToTree(jobs.report(deviceId, hostname, bots)));
            body.set("jobs", mapper.valueToTree(jobs.poll(deviceId, Duration.ZERO)));
            return body;
        });
    }

    @Tool(
            name = "finish_job",
            description = "Freedriver dispatch: post the result of one queued job so the caller can read it.")
    @RolesAllowed(Actors.DEVICE)
    String finishJob(
            @ToolArg(name = "assignmentId") @NotNull UUID assignmentId,
            @ToolArg(name = "succeeded") boolean succeeded,
            @ToolArg(name = "note", required = false) @Size(max = 2000) String note) {
        return guard(() -> jobs.finish(JobCallers.deviceId(identity), assignmentId, succeeded, note));
    }

    /** A refused store call is the tool's error result; its message names the reason. */
    private String guard(Supplier<Object> action) {
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
}
