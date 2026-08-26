package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class ApplianceBackendProducer {

    @Inject
    AppliancesConfig config;

    @Inject
    MockAutonomy mock;

    @Produces
    ApplianceBackend backend() {
        if (config.mockAutonomy()) {
            return mock;
        }
        if (!config.liveCommands()) {
            return new DisabledApplianceBackend();
        }
        throw new IllegalStateException("Live MQTT command adapter is not produced");
    }
}
