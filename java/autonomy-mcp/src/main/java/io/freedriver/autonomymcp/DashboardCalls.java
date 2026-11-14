package io.freedriver.autonomymcp;

import io.freedriver.dashboard.api.AppliancesApi;
import io.freedriver.dashboard.api.McpOpsApi;
import io.freedriver.dashboard.model.ApplianceCommandRequest;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.freedriver.dashboard.model.HomeStatusResponse;
import io.freedriver.dashboard.model.McpDebugResponse;
import io.freedriver.dashboard.model.ScheduledSwitchRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.ProcessingException;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The dashboard calls the MCP tools make. Each answer is a status and, on 2xx, the typed body:
 * the refused status from {@link DashboardResponses}, 400 when the client refuses an invalid
 * argument, or 503 when the dashboard is unreachable. No JAX-RS type leaves this class.
 */
@ApplicationScoped
public class DashboardCalls {

    private final AppliancesApi appliances;
    private final McpOpsApi ops;

    @Inject
    DashboardCalls(@RestClient DashboardAppliances appliances, @RestClient DashboardMcpOps ops) {
        this.appliances = appliances;
        this.ops = ops;
    }

    public Answer<ApplianceMapResponse> appliances() {
        return call(ops::appliances);
    }

    public Answer<HomeStatusResponse> homeStatus() {
        return call(ops::homeStatus);
    }

    public Answer<McpDebugResponse> debug() {
        return call(ops::debug);
    }

    public Answer<Void> command(UUID instanceId, String applianceName, boolean on) {
        ApplianceCommandRequest command = new ApplianceCommandRequest();
        command.setOn(on);
        return call(() -> {
            appliances.command(instanceId, applianceName, command);
            return null;
        });
    }

    public Answer<Void> schedule(UUID instanceId, String applianceName, boolean on, Instant dueAt) {
        ScheduledSwitchRequest body = new ScheduledSwitchRequest();
        body.setInstanceId(instanceId);
        body.setApplianceName(applianceName);
        body.setOn(on);
        body.setDueAt(dueAt);
        return call(() -> {
            ops.schedule(body).close();
            return null;
        });
    }

    private static <T> Answer<T> call(Supplier<T> request) {
        try {
            return new Answer<>(200, request.get());
        } catch (DashboardRefused refused) {
            return new Answer<>(refused.status(), null);
        } catch (ConstraintViolationException invalid) {
            return new Answer<>(400, null);
        } catch (ProcessingException unreachable) {
            return new Answer<>(503, null);
        }
    }

    /** {@code body} is set only on 2xx, and is null for a call with no answer body. */
    public record Answer<T>(int status, T body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
