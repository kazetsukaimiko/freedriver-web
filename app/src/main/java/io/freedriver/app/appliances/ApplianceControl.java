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
 * One router. Mock event sources and a later MQTT client talk to this same bean.
 * There is no mock/disabled/live backend implementation of this type.
 */
@ApplicationScoped
public class ApplianceControl {

    @Inject
    Event<ApplianceCommandRouted> commands;

    private final ConcurrentHashMap<UUID, KnownInstance> known = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<ApplianceSnapshot>> waiters = new ConcurrentHashMap<>();

    public Optional<String> instanceName(@NonNull UUID instanceId) {
        KnownInstance slot = known.get(instanceId);
        return slot == null ? Optional.empty() : Optional.of(slot.instanceName);
    }

    public Optional<ApplianceSnapshot> instanceSnapshot(@NonNull UUID instanceId) {
        KnownInstance slot = known.get(instanceId);
        return slot == null ? Optional.empty() : Optional.of(slot.snapshot);
    }

    /**
     * @return true if this instance is known and the command was fired on the bus
     */
    public boolean publishCommand(@NonNull UUID instanceId, @NonNull ApplianceCommandMessage command) {
        if (!known.containsKey(instanceId)) {
            return false;
        }
        commands.fire(new ApplianceCommandRouted(instanceId, command));
        return true;
    }

    public Optional<ApplianceSnapshot> publishCommandAndWait(
            @NonNull UUID instanceId, @NonNull ApplianceCommandMessage command, Duration timeout) {
        CompletableFuture<ApplianceSnapshot> future = new CompletableFuture<>();
        waiters.put(command.commandId(), future);
        try {
            if (!publishCommand(instanceId, command)) {
                return Optional.empty();
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
            waiters.remove(command.commandId());
        }
    }

    /** Map key = instanceId. Each instanceName is a dashboard tab. */
    public Map<UUID, ApplianceSnapshot> allKnownInstances() {
        Map<UUID, ApplianceSnapshot> out = new LinkedHashMap<>();
        known.forEach((id, slot) -> out.put(id, slot.snapshot));
        return Map.copyOf(out);
    }

    public Map<UUID, String> instanceNames() {
        Map<UUID, String> out = new LinkedHashMap<>();
        known.forEach((id, slot) -> out.put(id, slot.instanceName));
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
        known.put(instanceId, new KnownInstance(state.instanceName(), snapshot));
        String applied = state.appliedCommandId();
        if (applied != null) {
            CompletableFuture<ApplianceSnapshot> waiter = waiters.get(applied);
            if (waiter != null) {
                waiter.complete(snapshot);
            }
        }
    }

    /** Drop a known instance. Used by tests and later expiry. */
    public void forget(@NonNull UUID instanceId) {
        known.remove(instanceId);
    }

    /** Pretend the last state is older than the stale window. */
    public void markStale(@NonNull UUID instanceId, Duration window) {
        KnownInstance slot = known.get(instanceId);
        if (slot == null) {
            return;
        }
        Instant when = Instant.now().minus(window).minusSeconds(1);
        known.put(instanceId, new KnownInstance(slot.instanceName, new ApplianceSnapshot(when, slot.snapshot.appliances())));
    }

    private record KnownInstance(String instanceName, ApplianceSnapshot snapshot) {}
}
