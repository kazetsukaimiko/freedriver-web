package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.Appliance;
import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceStateMessage;
import jakarta.enterprise.inject.Typed;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

/** In-process fixture used by %dev and %test. Not the production backend. */
@ApplicationScoped
@Typed(FakeApplianceBackend.class)
public class FakeApplianceBackend implements ApplianceBackend {

    public static final UUID INSTANCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    public static final String INSTANCE_NAME = "Cabin";

    @Inject
    AppliancesConfig config;

    private final Object lock = new Object();
    private final List<ApplianceCommandMessage> commands = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<ApplianceSnapshot>> waiters = new ConcurrentHashMap<>();

    private volatile ApplianceStateMessage state;
    private volatile Instant receivedAt;
    private volatile boolean confirmCommands = true;
    private ScheduledExecutorService refresh;

    @PostConstruct
    void start() {
        if (config.liveCommands()) {
            throw new IllegalStateException("Fake backend must not run with live-commands=true");
        }
        if (config.fakeRefresh()) {
            publishState(new ApplianceStateMessage(
                    INSTANCE_ID,
                    INSTANCE_NAME,
                    null,
                    List.of(new Appliance("living-room-lamp", true))));
            refresh = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fake-appliance-refresh");
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

    @Override
    public ApplianceSnapshot snapshot() {
        synchronized (lock) {
            if (state == null) {
                return ApplianceSnapshot.never();
            }
            return new ApplianceSnapshot(
                    receivedAt,
                    state.instanceId(),
                    state.instanceName(),
                    state.appliedCommandId(),
                    state.appliances());
        }
    }

    @Override
    public void publishCommand(ApplianceCommandMessage command) {
        commands.add(command);
        if (confirmCommands) {
            apply(command);
        }
    }

    @Override
    public Optional<ApplianceSnapshot> awaitApplied(String commandId, Duration timeout) {
        ApplianceSnapshot current = snapshot();
        if (commandId.equals(current.appliedCommandId())) {
            return Optional.of(current);
        }
        CompletableFuture<ApplianceSnapshot> future = new CompletableFuture<>();
        waiters.put(commandId, future);
        try {
            current = snapshot();
            if (commandId.equals(current.appliedCommandId())) {
                return Optional.of(current);
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
        return List.copyOf(commands);
    }

    public void reset() {
        synchronized (lock) {
            state = null;
            receivedAt = null;
            confirmCommands = true;
            commands.clear();
            waiters.values().forEach(f -> f.cancel(true));
            waiters.clear();
        }
    }

    public void setConfirmCommands(boolean confirmCommands) {
        this.confirmCommands = confirmCommands;
    }

    public void publishState(ApplianceStateMessage message) {
        accept(message, Instant.now());
    }

    public void publishStateJson(String json) {
        publishState(ApplianceStateMessage.parse(json));
    }

    public void markStale() {
        synchronized (lock) {
            if (receivedAt == null) {
                return;
            }
            receivedAt = Instant.now().minus(config.staleAfter()).minusSeconds(1);
        }
    }

    private void apply(ApplianceCommandMessage command) {
        synchronized (lock) {
            if (state == null) {
                return;
            }
            List<Appliance> next = new ArrayList<>();
            for (Appliance appliance : state.appliances()) {
                if (appliance.applianceName().equals(command.applianceName())) {
                    next.add(new Appliance(appliance.applianceName(), command.on()));
                } else {
                    next.add(appliance);
                }
            }
            acceptLocked(new ApplianceStateMessage(
                    state.instanceId(), state.instanceName(), command.commandId(), next), Instant.now());
        }
    }

    private void republishCurrent() {
        try {
            synchronized (lock) {
                if (state != null) {
                    acceptLocked(state, Instant.now());
                }
            }
        } catch (RuntimeException e) {
            Log.warn("fake appliance refresh failed", e);
        }
    }

    private void accept(ApplianceStateMessage message, Instant when) {
        synchronized (lock) {
            acceptLocked(message, when);
        }
    }

    private void acceptLocked(ApplianceStateMessage message, Instant when) {
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
