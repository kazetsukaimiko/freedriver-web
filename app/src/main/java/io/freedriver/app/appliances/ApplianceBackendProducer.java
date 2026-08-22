package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class ApplianceBackendProducer {

    @Inject
    AppliancesConfig config;

    @Inject
    FakeApplianceBackend fake;

    @Produces
    ApplianceBackend backend() {
        if ("fake".equals(config.backend())) {
            return fake;
        }
        return new DisabledApplianceBackend();
    }
}
