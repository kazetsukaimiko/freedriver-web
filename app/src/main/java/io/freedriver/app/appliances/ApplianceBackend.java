package io.freedriver.app.appliances;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface ApplianceBackend {

    ApplianceSnapshot snapshot();

    void publishCommand(ApplianceCommandMessage command);

    Optional<ApplianceSnapshot> awaitApplied(String commandId, Duration timeout);

    List<ApplianceCommandMessage> publishedCommands();
}
