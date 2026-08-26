package io.freedriver.app.appliances;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.time.Instant;

@ApplicationScoped
public class ApplianceAudit {

    private static final Logger log = LoggerFactory.getLogger(ApplianceAudit.class);

    private final SecurityIdentity identity;

    public ApplianceAudit() {
        this(null);
    }

    @Inject
    public ApplianceAudit(SecurityIdentity identity) {
        this.identity = identity;
    }

    public void record(Event event) {
        log.info(
                "audit appliance user={} when={} appliance={} on={} commandId={} result={}",
                caller(),
                event.when(),
                event.applianceName(),
                event.on(),
                event.commandId(),
                event.result());
    }

    private String caller() {
        if (identity == null || identity.isAnonymous()) {
            return "-";
        }
        Principal principal = identity.getPrincipal();
        if (principal == null) {
            return "-";
        }
        String name = principal.getName();
        if (name == null || name.isBlank()) {
            return "-";
        }
        return name;
    }

    public record Event(
            Instant when,
            String applianceName,
            boolean on,
            String commandId,
            String result) {
    }
}
