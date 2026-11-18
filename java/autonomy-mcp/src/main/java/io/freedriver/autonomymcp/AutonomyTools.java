package io.freedriver.autonomymcp;

import io.freedriver.mcp.AuditedRefusals;
import io.freedriver.mcp.DeclaredToolArguments;
import io.quarkiverse.mcp.server.ToolGuardrails;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.McpDebugResponse;
import io.freedriver.mcp.CallerIds;
import io.freedriver.mcp.McpAudit;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Appliance tools on the Quarkus MCP server at {@code /autonomy/mcp}. Each tool calls the dashboard
 * through the REST clients as the MCP caller. The constraints on each argument are the validation
 * and the advertised input schema, and {@code @RolesAllowed} is the role check. A refusal from the
 * dashboard or the rate limit is a spoken tool error, and every decision past the role check is
 * one audit line.
 */
@ApplicationScoped
@AuditedRefusals
public class AutonomyTools {

    private final SecurityIdentity identity;
    private final ObjectMapper mapper;
    private final DashboardCalls dashboard;
    private final ToolRateLimiter limiter;
    private final McpAudit audit;

    @Inject
    public AutonomyTools(
            SecurityIdentity identity,
            ObjectMapper mapper,
            DashboardCalls dashboard,
            ToolRateLimiter limiter,
            McpAudit audit) {
        this.identity = identity;
        this.mapper = mapper;
        this.dashboard = dashboard;
        this.limiter = limiter;
        this.audit = audit;
    }

    @ToolGuardrails(input = DeclaredToolArguments.class)
    @Tool(
            name = "list_appliances",
            description = "Return a JSON array. Each object has houseName, instanceId, and appliances. "
                    + "Each appliance has name and state, on or off. "
                    + "Pass instanceId and appliance names to switch_appliances.")
    @RolesAllowed(Actors.MCP_USER)
    ToolResponse listAppliances() {
        return read("list_appliances", dashboard::appliances,
                (ApplianceMapResponse map) -> ApplianceList.json(mapper, map));
    }

    static final String SWITCH_APPLIANCES = "switch_appliances";
    static final int MAX_SWITCHES = 16;

    /**
     * Each entry is one dashboard call in list order: a command now, or a stored switch when its
     * delaySeconds is positive. Each entry takes one hit of the caller's tool limit, all or none, and
     * writes one audit line. A refused entry is that entry's sentence; the others still run.
     */
    @ToolGuardrails(input = DeclaredToolArguments.class)
    @Tool(
            name = SWITCH_APPLIANCES,
            description = "Switch one or more named appliances of one house on or off in one call. "
                    + "Put every appliance the person named into `appliances`, each with `on`. "
                    + "`delaySeconds` on an entry waits that many seconds, then switches; "
                    + "omit it or pass 0 to switch now. "
                    + "To leave a light on for five minutes, add it once with `on` true "
                    + "and once with `on` false and `delaySeconds` 300. "
                    + "Entries run in list order. The reply has one sentence per entry.")
    @RolesAllowed(Actors.DASHBOARD)
    ToolResponse switchAppliances(
            @ToolArg(name = "instanceId", description = "The house, from list_appliances.") @NotNull UUID instanceId,
            @ToolArg(name = "appliances",
                    description = "One entry per switch, at most 16: applianceName, on, and optional "
                            + "delaySeconds (five minutes is 300, the maximum is 86400).")
            @NotEmpty @Size(max = MAX_SWITCHES) List<@Valid @NotNull ApplianceSwitch> appliances) {
        String instance = instanceId.toString();
        Optional<String> twice = listedTwice(appliances);
        if (twice.isPresent()) {
            appliances.forEach(entry -> audit(SWITCH_APPLIANCES, instance, entry.applianceName(), "denied"));
            return ToolResponse.error(Spoken.listedTwice(twice.get()));
        }
        if (!limiter.tryAcquire(CallerIds.subject(identity), SWITCH_APPLIANCES, appliances.size())) {
            appliances.forEach(entry -> audit(SWITCH_APPLIANCES, instance, entry.applianceName(), "rate-limited"));
            return ToolResponse.error(Spoken.refusal(429));
        }
        List<String> sentences = new ArrayList<>(appliances.size());
        boolean anySwitched = false;
        for (ApplianceSwitch entry : appliances) {
            Outcome outcome = switchOne(instanceId, entry);
            audit(SWITCH_APPLIANCES, instance, entry.applianceName(), outcome.result());
            sentences.add(outcome.sentence());
            anySwitched |= outcome.ok();
        }
        String text = String.join(" ", sentences);
        return anySwitched ? ToolResponse.success(text) : ToolResponse.error(text);
    }

    private Outcome switchOne(UUID instanceId, ApplianceSwitch entry) {
        String name = entry.applianceName();
        boolean on = entry.on();
        int delay = entry.delay();
        if (delay > 0) {
            int status = dashboard.schedule(instanceId, name, on, Instant.now().plusSeconds(delay)).status();
            return ok(status)
                    ? new Outcome(true, "scheduled", Spoken.scheduled(name, on, delay))
                    : new Outcome(false, resultOf(status), Spoken.refused(name, status));
        }
        int status = dashboard.command(instanceId, name, on).status();
        return ok(status)
                ? new Outcome(true, resultOf(status), Spoken.switched(name, on))
                : new Outcome(false, resultOf(status), Spoken.refused(name, status));
    }

    /** The first appliance named twice with the same delay, if any. The same name with different delays is allowed. */
    static Optional<String> listedTwice(List<ApplianceSwitch> appliances) {
        Set<String> seen = new HashSet<>();
        for (ApplianceSwitch entry : appliances) {
            if (!seen.add(entry.delay() + ":" + entry.applianceName())) {
                return Optional.of(entry.applianceName());
            }
        }
        return Optional.empty();
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    private record Outcome(boolean ok, String result, String sentence) {
    }

    @ToolGuardrails(input = DeclaredToolArguments.class)
    @Tool(name = "home_status", description = "Broker connection and last state update.")
    @RolesAllowed(Actors.MCP_USER)
    ToolResponse homeStatus() {
        return read("home_status", dashboard::homeStatus, (HomeStatusResponse status) -> Spoken.home(status));
    }

    @ToolGuardrails(input = DeclaredToolArguments.class)
    @Tool(name = "debug", description = "Read-only health and recent error category.")
    @RolesAllowed(Actors.MCP_DEBUG)
    ToolResponse debug() {
        return read("debug", dashboard::debug, (McpDebugResponse debug) -> Spoken.debug(debug));
    }

    private <T> ToolResponse read(String tool, Supplier<DashboardCalls.Answer<T>> call, BodyText<T> bodyText) {
        if (!limiter.tryAcquire(CallerIds.subject(identity), tool)) {
            audit(tool, null, null, "rate-limited");
            return ToolResponse.error(Spoken.refusal(429));
        }
        DashboardCalls.Answer<T> answer = call.get();
        int status = answer.status();
        audit(tool, null, null, resultOf(status));
        if (!answer.ok()) {
            return ToolResponse.error(Spoken.refusal(status));
        }
        try {
            return ToolResponse.success(bodyText.render(answer.body()));
        } catch (Exception e) {
            audit(tool, null, null, "error");
            return ToolResponse.error(Spoken.refusal(500));
        }
    }

    private void audit(String tool, String instance, String appliance, String result) {
        audit.record(new McpAudit.Event(
                Instant.now(),
                CallerIds.subject(identity),
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

    @FunctionalInterface
    private interface BodyText<T> {
        String render(T body) throws Exception;
    }
}
