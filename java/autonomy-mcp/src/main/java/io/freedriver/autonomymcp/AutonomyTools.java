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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
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
                    + "Pass instanceId to switch_appliance.")
    @RolesAllowed(Actors.MCP_USER)
    ToolResponse listAppliances() {
        return read("list_appliances", dashboard::appliances,
                (ApplianceMapResponse map) -> ApplianceList.json(mapper, map));
    }

    @ToolGuardrails(input = DeclaredToolArguments.class)
    @Tool(
            name = "switch_appliance",
            description = "Switch one named appliance on or off. Same command path as the dashboard. "
                    + "delaySeconds waits that many seconds, then switches. "
                    + "Omit it or pass 0 to switch now. "
                    + "To leave the fridge light on for five minutes, switch it on with no delay, "
                    + "then switch it off with delaySeconds 300.")
    @RolesAllowed({Actors.DASHBOARD, Actors.PORTAL_ADMIN})
    ToolResponse switchAppliance(
            @ToolArg(name = "instanceId") @NotNull UUID instanceId,
            @ToolArg(name = "applianceName") @NotBlank @Size(max = 64) String applianceName,
            @ToolArg(name = "on") boolean on,
            @ToolArg(name = "delaySeconds", required = false,
                    description = "Seconds before this switch. Five minutes is 300. The maximum is 86400.")
            @Min(0) @Max(SwitchDelay.MAX_DELAY_SECONDS) Integer delaySeconds) {
        String subject = CallerIds.subject(identity);
        String instance = instanceId.toString();
        if (!limiter.tryAcquire(subject, "switch_appliance")) {
            audit("switch_appliance", instance, applianceName, "rate-limited");
            return ToolResponse.error(Spoken.refusal(429));
        }
        int delay = delaySeconds == null ? 0 : delaySeconds;
        if (delay > 0) {
            int status = dashboard.schedule(instanceId, applianceName, on, Instant.now().plusSeconds(delay)).status();
            boolean stored = status >= 200 && status < 300;
            audit("switch_appliance", instance, applianceName, stored ? "scheduled" : resultOf(status));
            return stored
                    ? ToolResponse.success(Spoken.scheduled(applianceName, on, delay))
                    : ToolResponse.error(Spoken.refusal(status));
        }
        int status = dashboard.command(instanceId, applianceName, on).status();
        audit("switch_appliance", instance, applianceName, resultOf(status));
        return status >= 200 && status < 300
                ? ToolResponse.success(Spoken.switched(applianceName, on))
                : ToolResponse.error(Spoken.refusal(status));
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
