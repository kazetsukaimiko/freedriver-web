package io.freedriver.app.schedule;

import io.freedriver.app.appliances.ApplianceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Runs a stored switch on the same command path as the dashboard. */
@ApplicationScoped
class ApplianceSwitchRunner implements SwitchRunner {

    private final ApplianceService appliances;

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    ApplianceSwitchRunner() {
        this.appliances = null;
    }

    @Inject
    ApplianceSwitchRunner(ApplianceService appliances) {
        this.appliances = appliances;
    }

    @Override
    public void run(ScheduledSwitch row) {
        appliances.issueCommand(row.instanceId(), row.applianceName(), row.on(), row.subject());
    }
}
