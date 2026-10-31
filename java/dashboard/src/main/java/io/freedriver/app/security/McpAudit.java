package io.freedriver.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@ApplicationScoped
public class McpAudit {

    private static final Logger log = LoggerFactory.getLogger(McpAudit.class);

    public void record(Event event) {
        log.info(line(event));
    }

    static String line(Event event) {
        return "audit mcp when=%s sub=%s azp=%s tool=%s instance=%s appliance=%s result=%s"
                .formatted(
                        event.when(),
                        dash(event.sub()),
                        dash(event.azp()),
                        dash(event.tool()),
                        dash(event.instance()),
                        dash(event.appliance()),
                        event.result());
    }

    static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public record Event(
            Instant when,
            String sub,
            String azp,
            String tool,
            String instance,
            String appliance,
            String result) {
    }
}
