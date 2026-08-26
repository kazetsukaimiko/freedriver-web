package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Production default when {@code freedriver.appliances.backend=none}.
 * No map, no commands, no broker connection. The API itself is 404 while
 * {@code freedriver.appliances.enabled=false} (see {@link AppliancesDisabledFilter}).
 * Not a second observe/publish path — {@link ApplianceTransport} is the later MQTT hook.
 */
public class DisabledApplianceBackend implements ApplianceBackend {

    @Override
    public Optional<ApplianceSnapshot> snapshot() {
        return Optional.empty();
    }

    @Override
    public void publishCommand(ApplianceCommandMessage command) {
        throw new IllegalStateException("Live appliance commands are off");
    }

    @Override
    public Optional<ApplianceSnapshot> awaitApplied(String commandId, Duration timeout) {
        return Optional.empty();
    }

    @Override
    public List<ApplianceCommandMessage> publishedCommands() {
        return List.of();
    }
}
