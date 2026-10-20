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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Dev/test event source on the same CDI bus as production. Not an ApplianceControl
 * implementation. Disable with {@code freedriver.appliances.mock=false}.
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
    private final LinkedHashMap<String, Boolean> house = new LinkedHashMap<>();
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

    void onCommand(@Observes ApplianceCommandRouted routed) {
        if (!config.mock()) {
            return;
        }
        if (!INSTANCE_ID.equals(routed.instanceId())) {
            return;
        }
        published.add(routed);
        if (!confirmCommands) {
            return;
        }
        apply(routed.command());
    }

    public List<ApplianceCommandRouted> publishedCommands() {
        return List.copyOf(published);
    }

    public void reset() {
        confirmCommands = true;
        published.clear();
        if (config.mock()) {
            restoreFixtures();
            emit(null);
        }
    }

    public void setConfirmCommands(boolean confirmCommands) {
        this.confirmCommands = confirmCommands;
    }

    private void apply(ApplianceCommandMessage command) {
        synchronized (lock) {
            if (!house.containsKey(command.applianceName())) {
                return;
            }
            house.put(command.applianceName(), command.state());
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

    private List<Appliance> appliances() {
        synchronized (lock) {
            return house.entrySet().stream()
                    .map(e -> new Appliance(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    private void emit(String appliedCommandId) {
        states.fire(new ApplianceStateRouted(
                INSTANCE_ID,
                new ApplianceStateMessage(INSTANCE_NAME, appliedCommandId, appliances())));
    }

    private void republishCurrent() {
        try {
            synchronized (lock) {
                if (house.isEmpty()) {
                    return;
                }
            }
            emit(null);
        } catch (RuntimeException e) {
            Log.warn("mock-autonomy refresh failed", e);
        }
    }
}
