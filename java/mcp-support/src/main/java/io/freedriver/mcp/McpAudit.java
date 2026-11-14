package io.freedriver.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;

/** One audit line per MCP tool decision. It names no token and no argument values beyond the target. */
@ApplicationScoped
public class McpAudit {

    private static final Logger LOG = Logger.getLogger(McpAudit.class);

    public void record(Event event) {
        LOG.info(line(event));
    }

    public static String line(Event event) {
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
