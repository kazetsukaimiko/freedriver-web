package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * quarkus:dev / test adapter. Observes command payloads on the one CDI bus and
 * fires {@link ApplianceStateMessage} with {@code appliedCommandId}. One mock
 * instance ({@code instanceId} + UX {@code instanceName}) and exactly six named
 * appliances; no boards and no board UUID. Disable with properties only
 * ({@code freedriver.appliances.backend=none} or
 * {@code freedriver.appliances.enabled=false}). Default/prod stays off.
 * Replaces FakeApplianceBackend — do not keep a third path.
 * A later MQTT client (#40) hooks the same bus; this is not a live broker.
 */
@ApplicationScoped
@Typed(MockAutonomy.class)
public class MockAutonomy implements ApplianceBackend {

    public static final UUID INSTANCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    public static final String INSTANCE_NAME = "Cabin";

    public static final List<String> FIXTURE_NAMES = List.of(
            "hallway", "kitchen", "living-room", "bedroom", "garage", "porch");

    @Inject
    AppliancesConfig config;

    @Inject
    Event<ApplianceCommandMessage> commands;

    @Inject
    Event<ApplianceStateMessage> states;

    private final Object lock = new Object();
    private final List<ApplianceCommandMessage> published = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<ApplianceSnapshot>> waiters = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Boolean> house = new LinkedHashMap<>();

    private volatile ApplianceStateMessage state;
    private volatile Instant receivedAt;
    private volatile boolean confirmCommands = true;
    private ScheduledExecutorService refresh;

    void start(@Observes StartupEvent event) {
        if (active() && config.liveCommands()) {
            throw new IllegalStateException("mock-autonomy must not run with live-commands=true");
        }
        if (!active()) {
            return;
        }
        restoreFixtures();
        emit(null);
        if (config.mockRefresh()) {
            refresh = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mock-autonomy-refresh");
                t.setDaemon(true);
                return t;
            });
            refresh.scheduleAtFixedRate(this::republishCurrent, 10, 10, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void stop() {
        if (refresh != null) {
            refresh.shutdownNow();
        }
    }

    /** Property-only switch. No code change to turn mock-autonomy off. */
    boolean active() {
        return config.enabled() && config.mockAutonomy();
    }

    void onCommand(@Observes ApplianceCommandMessage command) {
        if (!active()) {
            return;
        }
        published.add(command);
        if (!confirmCommands) {
            return;
        }
        apply(command);
    }

    void onState(@Observes ApplianceStateMessage message) {
        if (!active()) {
            return;
        }
        accept(message, Instant.now());
    }

    @Override
    public Optional<ApplianceSnapshot> snapshot() {
        synchronized (lock) {
            if (state == null) {
                return Optional.empty();
            }
            return Optional.of(new ApplianceSnapshot(
                    receivedAt,
                    state.instanceId(),
                    state.instanceName(),
                    state.appliedCommandId(),
                    state.appliances()));
        }
    }

    @Override
    public void publishCommand(ApplianceCommandMessage command) {
        if (config.liveCommands()) {
            throw new IllegalStateException("Live MQTT commands are off until #25 and #27");
        }
        if (!active()) {
            throw new IllegalStateException("Live appliance commands are off");
        }
        commands.fire(command);
    }

    @Override
    public Optional<ApplianceSnapshot> awaitApplied(String commandId, Duration timeout) {
        Optional<ApplianceSnapshot> current = snapshot();
        if (applied(current, commandId)) {
            return current;
        }
        CompletableFuture<ApplianceSnapshot> future = new CompletableFuture<>();
        waiters.put(commandId, future);
        try {
            current = snapshot();
            if (applied(current, commandId)) {
                return current;
            }
            return Optional.of(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        } finally {
            waiters.remove(commandId);
        }
    }

    @Override
    public List<ApplianceCommandMessage> publishedCommands() {
        return List.copyOf(published);
    }

    public void publishState(ApplianceStateMessage message) {
        adopt(message.appliances());
        states.fire(message);
    }

    public void publishStateJson(String json) {
        publishState(ApplianceStateMessage.parse(json));
    }

    public void reset() {
        confirmCommands = true;
        published.clear();
        synchronized (lock) {
            state = null;
            receivedAt = null;
            waiters.values().forEach(f -> f.cancel(true));
            waiters.clear();
        }
        if (active()) {
            restoreFixtures();
            emit(null);
        }
    }

    /** Drop the map without reseeding. GET is then stale / empty (never received). */
    public void clearState() {
        published.clear();
        synchronized (lock) {
            state = null;
            receivedAt = null;
            waiters.values().forEach(f -> f.cancel(true));
            waiters.clear();
        }
    }

    public void setConfirmCommands(boolean confirmCommands) {
        this.confirmCommands = confirmCommands;
    }

    public void markStale() {
        synchronized (lock) {
            if (receivedAt == null) {
                return;
            }
            receivedAt = Instant.now().minus(config.staleAfter()).minusSeconds(1);
        }
    }

    private static boolean applied(Optional<ApplianceSnapshot> snapshot, String commandId) {
        return snapshot.isPresent() && commandId.equals(snapshot.get().appliedCommandId());
    }

    private void apply(ApplianceCommandMessage command) {
        synchronized (lock) {
            if (!house.containsKey(command.applianceName())) {
                return;
            }
            house.put(command.applianceName(), command.on());
        }
        emit(command.commandId());
    }

    private void restoreFixtures() {
        synchronized (lock) {
            house.clear();
            for (String name : FIXTURE_NAMES) {
                house.put(name, false);
            }
        }
    }

    private void adopt(List<Appliance> appliances) {
        synchronized (lock) {
            house.clear();
            if (appliances == null) {
                return;
            }
            for (Appliance appliance : appliances) {
                house.put(appliance.applianceName(), appliance.on());
            }
        }
    }

    private List<Appliance> appliances() {
        synchronized (lock) {
            return house.entrySet().stream()
                    .map(e -> new Appliance(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    private void emit(String appliedCommandId) {
        states.fire(new ApplianceStateMessage(
                INSTANCE_ID, INSTANCE_NAME, appliedCommandId, appliances()));
    }

    private void republishCurrent() {
        try {
            synchronized (lock) {
                if (house.isEmpty()) {
                    return;
                }
            }
            emit(snapshot().map(ApplianceSnapshot::appliedCommandId).orElse(null));
        } catch (RuntimeException e) {
            Log.warn("mock-autonomy refresh failed", e);
        }
    }

    private void accept(ApplianceStateMessage message, Instant when) {
        synchronized (lock) {
            this.state = message;
            this.receivedAt = when;
            String applied = message.appliedCommandId();
            if (applied != null) {
                CompletableFuture<ApplianceSnapshot> waiter = waiters.get(applied);
                if (waiter != null) {
                    waiter.complete(new ApplianceSnapshot(
                            when,
                            message.instanceId(),
                            message.instanceName(),
                            applied,
                            message.appliances()));
                }
            }
        }
    }
}
