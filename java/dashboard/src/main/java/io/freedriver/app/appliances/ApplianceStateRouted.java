package io.freedriver.app.appliances;

import io.freedriver.mqtt.contract.ApplianceStateMessage;
import lombok.NonNull;

import java.util.UUID;

/** CDI bus envelope. {@code instanceId} is the route key and travels in the MQTT topic. */
public record ApplianceStateRouted(
        @NonNull UUID instanceId, @NonNull ApplianceStateMessage state) {}
