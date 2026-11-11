package io.freedriver.app.appliances;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The last appliance snapshot for each instance.
 * The instance id names the row. Callers read the list again after each write.
 */
interface ApplianceRoster {

    void save(UUID instanceId, String instanceName, ApplianceSnapshot snapshot);

    /** The stored row. Empty when that id has no row. */
    Optional<Row> find(UUID instanceId);

    Map<UUID, Row> list();

    void remove(UUID instanceId);

    record Row(String instanceName, ApplianceSnapshot snapshot) {}
}
