package io.freedriver.mcp;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.Map;

/**
 * The audit line for a tool call refused before its method runs: for its role, for an undeclared
 * argument, or by validation. Result {@code denied}, the caller's {@code sub} and {@code azp}, and the
 * target when the call names one.
 */
@ApplicationScoped
public class McpRefusals {

    private final McpAudit audit;
    private final Instance<SecurityIdentity> identity;
    private final Clock clock;

    @Inject
    public McpRefusals(McpAudit audit, Instance<SecurityIdentity> identity) {
        this(audit, identity, Clock.systemUTC());
    }

    McpRefusals(McpAudit audit, Instance<SecurityIdentity> identity, Clock clock) {
        this.audit = audit;
        this.identity = identity;
        this.clock = clock;
    }

    /** {@code arguments} are the call's argument values by name; {@code instanceId} and {@code applianceName} name the target. */
    public void denied(String tool, Map<String, ?> arguments) {
        SecurityIdentity caller = caller();
        audit.record(new McpAudit.Event(
                clock.instant(),
                CallerIds.subject(caller),
                CallerIds.azp(caller),
                tool,
                text(arguments.get("instanceId")),
                text(arguments.get("applianceName")),
                "denied"));
    }

    private SecurityIdentity caller() {
        try {
            return identity.isResolvable() ? identity.get() : null;
        } catch (ContextNotActiveException noRequest) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
