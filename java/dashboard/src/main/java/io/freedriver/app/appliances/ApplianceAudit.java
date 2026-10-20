package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class ApplianceAudit {

    private static final Logger log = LoggerFactory.getLogger(ApplianceAudit.class);

    public void record(Event event) {
        log.info(line(event));
    }

    static String line(Event event) {
        String subject = event.subject() == null ? "" : event.subject();
        return "audit appliance when=%s instance=%s appliance=%s on=%s commandId=%s result=%s subject=%s"
                .formatted(
                        event.when(),
                        event.instanceId(),
                        event.applianceName(),
                        event.on(),
                        event.commandId(),
                        event.result(),
                        subject);
    }

    public record Event(
            Instant when,
            UUID instanceId,
            String applianceName,
            boolean on,
            String commandId,
            String result,
            String subject) {

        public Event {
            if (subject != null && subject.isBlank()) {
                subject = null;
            }
        }
    }
}
