package io.freedriver.app.invites;

import io.freedriver.app.appliances.CommandRateLimiter;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Consumes an invite. The person-facing page is #167. This is the atomic single use.
 * Anonymous on purpose: the person is not signed in yet.
 */
@Path("/api/invites/use")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class InviteUseResource {

    private final InviteStore store;
    private final CommandRateLimiter limiter;

    @Inject
    public InviteUseResource(InviteStore store, CommandRateLimiter limiter) {
        this.store = store;
        this.limiter = limiter;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response use(UseInvite request) {
        if (!limiter.tryAcquire("invite-use")) {
            return Response.status(429).build();
        }
        String code = request == null ? null : request.code();
        boolean used = store.use(code) == InviteStore.UseResult.USED;
        if (!used) {
            return Response.status(409).entity(Map.of("used", false)).build();
        }
        return Response.ok(Map.of("used", true)).build();
    }

    public record UseInvite(String code) {
    }
}
