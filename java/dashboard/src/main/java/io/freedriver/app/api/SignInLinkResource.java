package io.freedriver.app.api;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Page the invite link opens. It does not start a session. The browser continues at {@code /login}.
 */
@Path(SignInLink.PATH)
@PermitAll
public class SignInLinkResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response page() {
        return Response.ok(SignInLink.PAGE, MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "no-store")
                .build();
    }
}
