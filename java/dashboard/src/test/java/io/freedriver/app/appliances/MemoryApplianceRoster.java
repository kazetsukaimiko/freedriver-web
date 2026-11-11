package io.freedriver.app.appliances;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Roster rows for the test profile. */
@Alternative
@Priority(1)
@ApplicationScoped
public class MemoryApplianceRoster implements ApplianceRoster {

    private final ConcurrentHashMap<UUID, Row> rows = new ConcurrentHashMap<>();

    @Override
    public void save(UUID instanceId, String instanceName, ApplianceSnapshot snapshot) {
        rows.put(instanceId, new Row(instanceName, snapshot));
    }

    @Override
    public Optional<Row> find(UUID instanceId) {
        return Optional.ofNullable(rows.get(instanceId));
    }

    @Override
    public Map<UUID, Row> list() {
        return Map.copyOf(new LinkedHashMap<>(rows));
    }

    @Override
    public void remove(UUID instanceId) {
        rows.remove(instanceId);
    }
}
