package io.freedriver.autonomymcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires due switches off the request thread. The MCP call returns when the row is stored.
 */
@ApplicationScoped
public class DelayedSwitchPump {

    private static final Logger log = LoggerFactory.getLogger(DelayedSwitchPump.class);
    private static final int MAX_PER_TICK = 16;

    private final DelayedSwitchQueue queue;
    private final DashboardClient dashboard;
    private final McpAudit audit;
    private final ObjectMapper mapper;
    private ScheduledExecutorService executor;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    DelayedSwitchPump() {
        this.queue = null;
        this.dashboard = null;
        this.audit = null;
        this.mapper = null;
    }

    @Inject
    DelayedSwitchPump(DelayedSwitchQueue queue, DashboardClient dashboard, McpAudit audit, ObjectMapper mapper) {
        this.queue = queue;
        this.dashboard = dashboard;
        this.audit = audit;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autonomy-switch-delay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Posts switches that are due at {@code now}. Tests pass a clock ahead of the delay. */
    public void fireDue(Instant now) {
        long nowMillis = now.toEpochMilli();
        for (int n = 0; n < MAX_PER_TICK; n++) {
            String id = queue.lockDue(nowMillis);
            if (id == null) {
                return;
            }
            String raw = queue.payload(id);
            DelayedSwitchPayload.Parsed parsed = raw == null ? null : DelayedSwitchPayload.parse(mapper, raw);
            if (parsed == null) {
                queue.remove(id);
                continue;
            }
            DashboardClient.Call call = dashboard.post(
                    "/api/appliances/" + parsed.instanceId() + "/" + parsed.applianceName(),
                    "{\"on\":" + parsed.on() + "}",
                    parsed.subject(),
                    parsed.roles());
            audit.record(new McpAudit.Event(
                    Instant.now(),
                    parsed.subject(),
                    parsed.azp(),
                    "switch_appliance",
                    parsed.instanceId().toString(),
                    parsed.applianceName(),
                    McpResource.resultOf(call.status())));
            queue.remove(id);
        }
    }

    private void tick() {
        try {
            fireDue(Instant.now());
        } catch (Exception ex) {
            log.warn("delayed switch tick failed: {}", ex.getClass().getSimpleName());
        }
    }
}
