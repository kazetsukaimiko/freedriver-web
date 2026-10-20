package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.Appliance;
import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.mqtt.contract.ApplianceStateMessage;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dev/test event source that talks to {@link ApplianceControl} over the same CDI bus as
 * production. Disable with {@code freedriver.appliances.mock=false}.
 * <p>
 * Holds a map of autonomy instance id to instance name and appliance on/off.
 * Startup seeds the Cabin fixture only. A command for an unknown instance id is ignored.
 */
@ApplicationScoped
public class MockAutonomy {

    public static final UUID INSTANCE_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    public static final String INSTANCE_NAME = "Cabin";
    public static final List<String> FIXTURE_NAMES = List.of(
            "hallway", "kitchen", "living-room", "bedroom", "garage", "porch");

    @Inject
    AppliancesConfig config;

    @Inject
    Event<ApplianceStateRouted> states;

    private final Object lock = new Object();
    private final Map<UUID, InstanceSlot> instances = new LinkedHashMap<>();
    private final List<ApplianceCommandRouted> published = new CopyOnWriteArrayList<>();
    private volatile boolean confirmCommands = true;
    private ScheduledExecutorService refresh;

    void start(@Observes StartupEvent event) {
        if (config.mock() && config.liveCommands()) {
            throw new IllegalStateException("mock-autonomy must not run with live-commands=true");
        }
        if (!config.mock()) {
            return;
        }
        restoreFixtures();
        emit(INSTANCE_ID, null);
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

    void onCommand(@Observes ApplianceCommandRouted routed) {
        if (!config.mock()) {
            return;
        }
        synchronized (lock) {
            if (!instances.containsKey(routed.instanceId())) {
                return;
            }
        }
        published.add(routed);
        if (!confirmCommands) {
            return;
        }
        apply(routed.instanceId(), routed.command());
    }

    public List<ApplianceCommandRouted> publishedCommands() {
        return List.copyOf(published);
    }

    public void reset() {
        confirmCommands = true;
        published.clear();
        if (config.mock()) {
            restoreFixtures();
            emit(INSTANCE_ID, null);
        }
    }

    /**
     * Add or replace one simulated instance and publish its state on the existing bus.
     */
    public void seedInstance(UUID instanceId, String instanceName, List<String> applianceNames) {
        if (!config.mock()) {
            return;
        }
        synchronized (lock) {
            instances.put(instanceId, new InstanceSlot(instanceName, applianceNames));
        }
        emit(instanceId, null);
    }

    public void setConfirmCommands(boolean confirmCommands) {
        this.confirmCommands = confirmCommands;
    }

    private void apply(UUID instanceId, ApplianceCommandMessage command) {
        synchronized (lock) {
            InstanceSlot slot = instances.get(instanceId);
            if (slot == null || !slot.appliances.containsKey(command.applianceName())) {
                return;
            }
            slot.appliances.put(command.applianceName(), command.state());
        }
        emit(instanceId, command.commandId());
    }

    private void restoreFixtures() {
        synchronized (lock) {
            instances.clear();
            instances.put(INSTANCE_ID, new InstanceSlot(INSTANCE_NAME, FIXTURE_NAMES));
        }
    }

    private void emit(UUID instanceId, String appliedCommandId) {
        String instanceName;
        List<Appliance> appliances;
        synchronized (lock) {
            InstanceSlot slot = instances.get(instanceId);
            if (slot == null) {
                return;
            }
            instanceName = slot.name;
            appliances = new ArrayList<>(slot.appliances.size());
            slot.appliances.forEach((name, on) -> appliances.add(new Appliance(name, on)));
        }
        states.fire(new ApplianceStateRouted(
                instanceId,
                new ApplianceStateMessage(instanceName, appliedCommandId, appliances)));
    }

    private void republishCurrent() {
        try {
            List<UUID> ids;
            synchronized (lock) {
                if (instances.isEmpty()) {
                    return;
                }
                ids = List.copyOf(instances.keySet());
            }
            for (UUID id : ids) {
                emit(id, null);
            }
        } catch (RuntimeException e) {
            Log.warn("mock-autonomy refresh failed", e);
        }
    }

    /** Name plus appliance on/off for one autonomy instance. */
    private static final class InstanceSlot {
        private final String name;
        private final LinkedHashMap<String, Boolean> appliances = new LinkedHashMap<>();

        private InstanceSlot(String name, List<String> applianceNames) {
            this.name = name;
            for (String applianceName : applianceNames) {
                appliances.put(applianceName, false);
            }
        }
    }
}
