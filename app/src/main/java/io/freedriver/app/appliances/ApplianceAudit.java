package io.freedriver.app.appliances;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;

@ApplicationScoped
public class ApplianceAudit {

    public void record(String user, Instant when, String name, boolean on, String commandId, String result) {
        Log.infof(
                "audit appliance user=%s when=%s appliance=%s on=%s commandId=%s result=%s",
                user, when, name, on, commandId, result);
    }
}
