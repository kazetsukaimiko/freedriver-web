package io.freedriver.app.invites;

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
 * Anonymous invite page. The code arrives in the JSON body, never in the query string.
 * A correct code does not mint a session. The browser continues at {@code /login}.
 */
@Path("/api/invites/redeem")
@PermitAll
@Produces(MediaType.APPLICATION_JSON)
public class InviteRedeemResource {

    private final InviteRedeem redeem;

    @Inject
    public InviteRedeemResource(InviteRedeem redeem) {
        this.redeem = redeem;
    }

    @POST
    @Path("start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(Start request) {
        String code = request == null ? null : request.code();
        String phone = request == null ? null : request.phone();
        String wording = request == null ? null : request.wording();
        return respond(redeem.start(code, phone, wording));
    }

    @POST
    @Path("resend")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response resend(CodeBody request) {
        return respond(redeem.resend(request == null ? null : request.code()));
    }

    @POST
    @Path("verify")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response verify(Verify request) {
        String code = request == null ? null : request.code();
        String otp = request == null ? null : request.otp();
        return respond(redeem.verify(code, otp));
    }

    private static Response respond(InviteRedeem.Answer answer) {
        if (answer.verified()) {
            return Response.ok(Map.of("next", "/login")).build();
        }
        if (answer.error() == null) {
            return Response.ok(Map.of("codesSent", answer.codesSent())).build();
        }
        return Response.status(answer.status()).entity(Map.of("error", answer.error())).build();
    }

    public record Start(String code, String phone, String wording) {
    }

    public record CodeBody(String code) {
    }

    public record Verify(String code, String otp) {
    }
}
