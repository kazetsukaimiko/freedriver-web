package io.freedriver.app.appliances;

import io.freedriver.app.metrics.IoTimer;
import io.freedriver.app.metrics.MqttHandlerTimer;
import io.freedriver.app.mqttlog.HouseMqttLog;
import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.contract.ApplianceJson;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.micrometer.core.annotation.Timed;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live MQTT adapter on the same CDI bus as {@link MockAutonomy}.
 * {@link SubscribedHouses} holds the houses whose appliance state it follows; the session
 * connects when the first house is followed. Command publishing stays behind
 * {@code live-commands}. A failed state connection or a failed state write does not stop the
 * portal. Logs on {@code io.freedriver.mqtt}.
 */
@Timed(IoTimer.BLOCKING)
@ApplicationScoped
public class MqttLiveClient {

    /** State payloads larger than this are dropped before they are parsed. */
    static final int MAX_STATE_BYTES = 64 * 1024;

    private final AppliancesConfig appliances;
    private final SubscribedHouses subscriptions;
    private final Instance<MqttConnection> connections;
    private final Event<ApplianceStateRouted> states;
    private final MqttHandlerTimer handlers;
    private final ConcurrentHashMap<UUID, Traffic> traffic = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private MqttConnection connection;
    private ScheduledExecutorService scheduler;

    @Inject
    public MqttLiveClient(
            AppliancesConfig appliances,
            SubscribedHouses subscriptions,
            Instance<MqttConnection> connections,
            Event<ApplianceStateRouted> states,
            MqttHandlerTimer handlers) {
        this.appliances = appliances;
        this.subscriptions = subscriptions;
        this.connections = connections;
        this.states = states;
        this.handlers = handlers;
    }

    void start(@Observes StartupEvent event) {
        if (appliances.liveCommands() && appliances.mock()) {
            throw new IllegalStateException("live MQTT must not run with mock=true");
        }
    }

    /**
     * Subscribes to one house's appliance state, connecting the session first when this is the
     * first house. A failed connect closes the session and throws; the next call tries again.
     */
    synchronized void follow(UUID instanceId) {
        connect();
        connection.subscribe(ApplianceSchemas.appliancesTopic(instanceId), ApplianceSchemas.QOS,
                handlers.timed(MqttLiveClient.class, this::onBrokerMessage));
    }

    /** Stops one house's appliance state. The session stays open for the other houses. */
    synchronized void unfollow(UUID instanceId) {
        if (connection != null) {
            connection.unsubscribe(ApplianceSchemas.appliancesTopic(instanceId));
        }
    }

    private void connect() {
        if (connection != null) {
            return;
        }
        try {
            MqttConnection opened = connections.get();
            opened.connect();
            connection = opened;
        } catch (RuntimeException e) {
            HouseMqttLog.connectFailed();
            stop();
            throw e;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mqtt-live");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::logStateSummary, 1, 1, TimeUnit.MINUTES);
    }

    public boolean acceptsCommands() {
        return appliances.liveCommands() && connection != null;
    }

    /** True while the MQTT session is connected to the broker, not only opened. */
    public boolean brokerConnected() {
        MqttConnection current = connection;
        return current != null && current.connected();
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
            HouseMqttLog.commandFailed(commandId, instanceId);
            return;
        }
        HouseMqttLog.commandSent(commandId, instanceId, appliance, state, user);
        ScheduledExecutorService exec = scheduler;
        if (exec == null) {
            return;
        }
        exec.schedule(() -> {
            if (pending.remove(commandId, tracked)) {
                HouseMqttLog.commandUnacknowledged(commandId, instanceId, appliance, state, user, timeoutMs);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    void onBrokerMessage(String topic, byte[] payload) {
        UUID instanceId = MqttAcl.instanceIdFrom(topic, "appliances");
        if (instanceId == null || !subscriptions.contains(instanceId)) {
            HouseMqttLog.stateRejected(topic, "unknown-instance");
            return;
        }
        if (payload != null && payload.length > MAX_STATE_BYTES) {
            HouseMqttLog.stateRejected(topic, "size");
            return;
        }
        final ApplianceStateMessage state;
        try {
            String json = payload == null ? "" : new String(payload, StandardCharsets.UTF_8);
            state = ApplianceJson.readState(json);
        } catch (RuntimeException e) {
            HouseMqttLog.stateRejected(topic, "schema");
            return;
        }
        traffic.computeIfAbsent(instanceId, id -> new Traffic()).mark();
        HouseMqttLog.stateReceived(instanceId, state.appliances().size());
        if (state.appliedCommandId() != null) {
            pending.remove(state.appliedCommandId());
        }
        try {
            states.fire(new ApplianceStateRouted(instanceId, state));
        } catch (RuntimeException e) {
            // A failed store must not end the MQTT callback thread; the next message is handled.
            HouseMqttLog.stateRejected(topic, "store-failed");
        }
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
        for (UUID instanceId : subscriptions.subscribed()) {
            Traffic heard = traffic.get(instanceId);
            long messages = heard == null ? 0 : heard.messages.get();
            long last = heard == null ? -1 : heard.lastHeardEpochMs.get();
            HouseMqttLog.stateSummary(instanceId, messages, lastHeardAgo(now, last));
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

    @PreDestroy
    synchronized void stop() {
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
