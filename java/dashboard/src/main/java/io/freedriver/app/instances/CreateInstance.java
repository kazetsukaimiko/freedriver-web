package io.freedriver.app.instances;

import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.validation.constraints.Size;

/** JSON body of {@code POST /api/instances}. */
public record CreateInstance(@Size(max = ApplianceSchemas.NAME_MAX) String label) {
}
