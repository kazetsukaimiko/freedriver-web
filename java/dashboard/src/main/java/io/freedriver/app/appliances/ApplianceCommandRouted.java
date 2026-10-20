package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.ApplianceCommandMessage;
import lombok.NonNull;

import java.util.UUID;

/** CDI bus envelope. {@code instanceId} is the route key; it is not in the MQTT body. */
public record ApplianceCommandRouted(
        @NonNull UUID instanceId, @NonNull ApplianceCommandMessage command) {}
