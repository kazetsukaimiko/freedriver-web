package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Production default. No map, no commands, no broker connection. */
public class DisabledApplianceBackend implements ApplianceBackend {

    @Override
    public ApplianceSnapshot snapshot() {
        return ApplianceSnapshot.never();
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
