package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@ApplicationScoped
public class ApplianceAudit {

    private static final Logger log = LoggerFactory.getLogger(ApplianceAudit.class);

    public void record(Event event) {
        log.info(
                "audit appliance when={} appliance={} on={} commandId={} result={}",
                event.when(),
                event.applianceName(),
                event.on(),
                event.commandId(),
                event.result());
    }

    public record Event(
            Instant when,
            String applianceName,
            boolean on,
            String commandId,
            String result) {
    }
}
