package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The single appliance router. {@link MockAutonomy} and {@link MqttLiveClient} both talk to
 * this bean over the CDI bus. Instance rows live in {@link ApplianceRoster}.
 */
@ApplicationScoped
public class ApplianceControl {

    @Inject
    Event<ApplianceCommandRouted> commands;

    private final ApplianceRoster roster;
    private final ConcurrentHashMap<String, CompletableFuture<ApplianceSnapshot>> waiters = new ConcurrentHashMap<>();

    @Inject
    ApplianceControl(ApplianceRoster roster) {
        this.roster = roster;
    }

    /** One read of the instance's row: its name and last snapshot. */
    public Optional<ApplianceRoster.Row> instance(@NonNull UUID instanceId) {
        return roster.find(instanceId);
    }

    /** One read of every row. Map key = instanceId. */
    public Map<UUID, ApplianceRoster.Row> instances() {
        return roster.list();
    }

    public Optional<String> instanceName(@NonNull UUID instanceId) {
        return roster.find(instanceId).map(ApplianceRoster.Row::instanceName);
    }

    public Optional<ApplianceSnapshot> instanceSnapshot(@NonNull UUID instanceId) {
        return roster.find(instanceId).map(ApplianceRoster.Row::snapshot);
    }

    /**
     * @return true if this instance is known and the command was fired on the bus
     */
    public boolean publishCommand(@NonNull UUID instanceId, @NonNull ApplianceCommandMessage command) {
        if (roster.find(instanceId).isEmpty()) {
            return false;
        }
        commands.fire(new ApplianceCommandRouted(instanceId, command));
        return true;
    }

    public Optional<ApplianceSnapshot> publishCommandAndWait(
            @NonNull UUID instanceId, @NonNull ApplianceCommandMessage command, Duration timeout) {
        if (roster.find(instanceId).isEmpty()) {
            return Optional.empty();
        }
        return publishKnownAndWait(instanceId, command, timeout);
    }

    /**
     * Fires the command for an instance the caller has already read, and waits for the state that
     * applies it. Reads no row.
     */
    public Optional<ApplianceSnapshot> publishKnownAndWait(
            @NonNull UUID instanceId, @NonNull ApplianceCommandMessage command, Duration timeout) {
        CompletableFuture<ApplianceSnapshot> future = new CompletableFuture<>();
        waiters.put(command.commandId(), future);
        try {
            commands.fire(new ApplianceCommandRouted(instanceId, command));
            return Optional.of(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty();
        } finally {
            waiters.remove(command.commandId());
        }
    }

    /** Map key = instanceId. Each instanceName is a dashboard tab. */
    public Map<UUID, ApplianceSnapshot> allKnownInstances() {
        Map<UUID, ApplianceSnapshot> out = new LinkedHashMap<>();
        roster.list().forEach((id, row) -> out.put(id, row.snapshot()));
        return Map.copyOf(out);
    }

    public Map<UUID, String> instanceNames() {
        Map<UUID, String> out = new LinkedHashMap<>();
        roster.list().forEach((id, row) -> out.put(id, row.instanceName()));
        return Map.copyOf(out);
    }

    void onState(@Observes ApplianceStateRouted routed) {
        UUID instanceId = routed.instanceId();
        ApplianceStateMessage state = routed.state();
        if (instanceId == null || state.instanceName() == null || state.instanceName().isBlank()) {
            return;
        }
        Instant when = Instant.now();
        ApplianceSnapshot snapshot = new ApplianceSnapshot(when, state.appliances());
        roster.save(instanceId, state.instanceName(), snapshot);
        String applied = state.appliedCommandId();
        if (applied != null) {
            CompletableFuture<ApplianceSnapshot> waiter = waiters.get(applied);
            if (waiter != null) {
                waiter.complete(snapshot);
            }
        }
    }
}
