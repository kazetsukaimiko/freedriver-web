package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.MqttLog;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live MQTT adapter on the same CDI bus as {@link MockAutonomy}.
 * Connects and subscribes to appliance state when instance ids are set, from
 * {@code freedriver.mqtt.instance-ids} or the host file behind it.
 * Command publishing stays behind {@code live-commands}. A failed state connection
 * does not stop the portal. Logs on {@code io.freedriver.mqtt}.
 */
@ApplicationScoped
public class MqttLiveClient {

    /** State payloads larger than this are dropped before they are parsed. */
    static final int MAX_STATE_BYTES = 64 * 1024;

    private final AppliancesConfig appliances;
    private final MqttSettings mqtt;
    private final Instance<MqttConnection> connections;
    private final Event<ApplianceStateRouted> states;
    private final ConcurrentHashMap<UUID, Traffic> traffic = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private MqttConnection connection;
    private ScheduledExecutorService scheduler;

    @Inject
    public MqttLiveClient(
            AppliancesConfig appliances,
            MqttSettings mqtt,
            Instance<MqttConnection> connections,
            Event<ApplianceStateRouted> states) {
        this.appliances = appliances;
        this.mqtt = mqtt;
        this.connections = connections;
        this.states = states;
    }

    void start(@Observes StartupEvent event) {
        if (appliances.liveCommands() && appliances.mock()) {
            throw new IllegalStateException("live MQTT must not run with mock=true");
        }
        List<UUID> instances = mqtt.instances();
        if (instances.isEmpty()) {
            if (appliances.liveCommands()) {
                throw new IllegalStateException("live-commands requires freedriver.mqtt.instance-ids");
            }
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mqtt-live");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::logStateSummary, 1, 1, TimeUnit.MINUTES);
        try {
            connection = connections.get();
            connection.connect();
            for (UUID instanceId : instances) {
                String topic = ApplianceSchemas.appliancesTopic(instanceId);
                connection.subscribe(topic, ApplianceSchemas.QOS, this::onBrokerMessage);
            }
        } catch (RuntimeException e) {
            stop();
            if (appliances.liveCommands()) {
                throw e;
            }
            MqttLog.connectFailed();
        }
    }

    public boolean acceptsCommands() {
        return appliances.liveCommands() && connection != null;
    }

    public void onCommand(ApplianceCommandRouted routed, String actor) {
        if (!appliances.liveCommands() || connection == null) {
            return;
        }
        String commandId = routed.command().commandId();
        UUID instanceId = routed.instanceId();
        String appliance = routed.command().applianceName();
        boolean state = routed.command().state();
        String user = actor == null || actor.isBlank() ? "unknown" : actor;
        long timeoutMs = appliances.boundedCommandTimeout().toMillis();
        Pending tracked = new Pending(commandId, instanceId, appliance, state, user, timeoutMs);
        pending.put(commandId, tracked);
        String topic = ApplianceSchemas.commandsTopic(instanceId);
        try {
            connection.publish(
                    topic,
                    ApplianceJson.writeCommand(routed.command()).getBytes(StandardCharsets.UTF_8),
                    ApplianceSchemas.QOS,
                    ApplianceSchemas.RETAIN);
        } catch (RuntimeException e) {
            pending.remove(commandId, tracked);
            MqttLog.commandFailed(commandId, instanceId);
            return;
        }
        MqttLog.commandSent(commandId, instanceId, appliance, state, user);
        ScheduledExecutorService exec = scheduler;
        if (exec == null) {
            return;
        }
        exec.schedule(() -> {
            if (pending.remove(commandId, tracked)) {
                MqttLog.commandUnacknowledged(commandId, instanceId, appliance, state, user, timeoutMs);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    void onBrokerMessage(String topic, byte[] payload) {
        UUID instanceId = MqttAcl.instanceIdFrom(topic, "appliances");
        if (instanceId == null || !mqtt.instances().contains(instanceId)) {
            MqttLog.stateRejected(topic, "unknown-instance");
            return;
        }
        if (payload != null && payload.length > MAX_STATE_BYTES) {
            MqttLog.stateRejected(topic, "size");
            return;
        }
        final ApplianceStateMessage state;
        try {
            String json = payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
            state = ApplianceJson.readState(json);
        } catch (RuntimeException e) {
            MqttLog.stateRejected(topic, "schema");
            return;
        }
        traffic.computeIfAbsent(instanceId, id -> new Traffic()).mark();
        MqttLog.stateReceived(instanceId, state.appliances().size());
        if (state.appliedCommandId() != null) {
            pending.remove(state.appliedCommandId());
        }
        states.fire(new ApplianceStateRouted(instanceId, state));
    }

    /** Once a minute. A failed pass must not cancel the next one. */
    void logStateSummary() {
        try {
            logStateSummary(Instant.now());
        } catch (RuntimeException ignored) {
            // The next minute still runs.
        }
    }

    void logStateSummary(Instant now) {
        for (UUID instanceId : mqtt.instances()) {
            Traffic heard = traffic.get(instanceId);
            long messages = heard == null ? 0 : heard.messages.get();
            long last = heard == null ? -1 : heard.lastHeardEpochMs.get();
            MqttLog.stateSummary(instanceId, messages, lastHeardAgo(now, last));
        }
    }

    static String lastHeardAgo(Instant now, long epochMs) {
        if (epochMs < 0) {
            return "never";
        }
        long seconds = Duration.between(Instant.ofEpochMilli(epochMs), now).toSeconds();
        if (seconds < 0) {
            seconds = 0;
        }
        return seconds + "s";
    }

    void resetTracking() {
        pending.clear();
        traffic.clear();
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    private record Pending(
            String commandId,
            UUID instanceId,
            String appliance,
            boolean state,
            String user,
            long timeoutMs) {
    }

    private static final class Traffic {
        private final AtomicLong messages = new AtomicLong();
        private final AtomicLong lastHeardEpochMs = new AtomicLong(-1);

        void mark() {
            messages.incrementAndGet();
            lastHeardEpochMs.set(System.currentTimeMillis());
        }
    }
}
