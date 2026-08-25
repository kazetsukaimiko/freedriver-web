package io.freedriver.app.appliances;

import io.freedriver.autonomy.mqtt.contract.ApplianceCommandMessage;
import io.freedriver.autonomy.mqtt.contract.ApplianceSchemas;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ApplianceService {

    @Inject
    AppliancesConfig config;

    @Inject
    ApplianceBackend backend;

    @Inject
    CommandRateLimiter rateLimiter;

    @Inject
    ApplianceAudit audit;

    public void assertCanAccess(SecurityIdentity identity) {
        if (!config.authRequired()) {
            return;
        }
        if (identity == null || identity.isAnonymous()) {
            throw new NotAuthorizedException("Bearer");
        }
        if (!identity.hasRole("dashboard") && !identity.hasRole("portal-admin")) {
            throw new ForbiddenException();
        }
    }

    public ApplianceMapResponse currentMap() {
        Instant now = Instant.now();
        ApplianceSnapshot snapshot = backend.snapshot();
        return snapshot.toResponse(snapshot.stale(config.staleAfter(), now), false);
    }

    public ApplianceMapResponse issueCommand(SecurityIdentity identity, String name, boolean on) {
        if (config.liveCommands()) {
            throw new IllegalStateException("Live MQTT commands are off until #25 and #27");
        }
        String user = userName(identity);
        if (!rateLimiter.tryAcquire(user)) {
            throw new ClientErrorException(Response.status(429).entity(new ErrorBody("rate_limited")).build());
        }

        Instant now = Instant.now();
        ApplianceSnapshot snapshot = backend.snapshot();
        if (snapshot.stale(config.staleAfter(), now)) {
            throw new ClientErrorException(
                    Response.status(409).entity(snapshot.toResponse(true, false)).build());
        }
        if (!ApplianceSchemas.validName(name) || snapshot.find(name).isEmpty()) {
            throw new NotFoundException();
        }

        String commandId = UUID.randomUUID().toString();
        ApplianceCommandMessage command = new ApplianceCommandMessage(
                ApplianceSchemas.SCHEMA_VERSION, commandId, name, on);
        backend.publishCommand(command);

        Duration wait = config.boundedCommandTimeout();
        Optional<ApplianceSnapshot> confirmed = backend.awaitApplied(commandId, wait);
        Instant auditedAt = Instant.now();
        if (confirmed.isPresent()) {
            audit.record(user, auditedAt, name, on, commandId, "confirmed");
            ApplianceSnapshot applied = confirmed.get();
            return applied.toResponse(applied.stale(config.staleAfter(), Instant.now()), false);
        }
        audit.record(user, auditedAt, name, on, commandId, "timeout");
        ApplianceSnapshot last = backend.snapshot();
        return last.toResponse(last.stale(config.staleAfter(), Instant.now()), true);
    }

    private static String userName(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "anonymous";
        }
        String name = identity.getPrincipal().getName();
        return name == null || name.isBlank() ? "anonymous" : name;
    }

    public record ErrorBody(String error) {
    }
}
