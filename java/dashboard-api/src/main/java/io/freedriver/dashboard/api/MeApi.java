package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.api.SignedInUser;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

/**
 * The signed-in caller. The route answers only that caller.
 * {@code subject} is the verified token's OIDC {@code sub}, the same value ApplianceAudit records,
 * and it is present and non-blank on a 200.
 * A token whose {@code sub} is absent or blank ({@code OidcSubjects.subject} returns null) gets 401,
 * never a 200 with a null or empty subject.
 * {@code name} is that token's OIDC {@code name} claim, used as-is, with no fallback from the
 * principal name, {@code preferred_username}, a phone number, an email, or any caller-supplied identity.
 * When the claim is absent or blank, {@code name} is omitted and is never sent as null.
 * <p>
 * 200 is the caller's own subject and name.
 * 401 is a bearer token that fails (invalid, expired, another issuer or audience) or carries no {@code sub}.
 * 403 is an authenticated caller with neither {@code dashboard} nor {@code portal-admin}.
 * Neither 401 nor 403 returns identity fields.
 * A request with no token gets the cookie sign-in answer (499 for a JavaScript call, otherwise a redirect to Keycloak).
 */
@Path("/api/me")
@Produces(MediaType.APPLICATION_JSON)
public interface MeApi {

    /**
     * The caller's own subject and name.
     */
    @GET
    @RolesAllowed({"dashboard", "portal-admin"})
    @APIResponses({
            @APIResponse(responseCode = "200",
                    description = "The caller's own subject and name. name is the OIDC name claim. "
                            + "When that claim is absent or blank, the name key is omitted and is never sent as null.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = SignedInUser.class))),
            @APIResponse(responseCode = "401",
                    description = "A bearer token that fails (invalid, expired, another issuer or audience) "
                            + "or carries no sub. The response returns no identity fields."),
            @APIResponse(responseCode = "403",
                    description = "An authenticated caller with neither dashboard nor portal-admin. "
                            + "The response returns no identity fields.")
    })
    SignedInUser me();
}
