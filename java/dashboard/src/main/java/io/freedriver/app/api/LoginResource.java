package io.freedriver.app.api;

import io.freedriver.dashboard.api.LoginApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@ApplicationScoped
@Path("/login")
public class LoginResource implements LoginApi {

    @Override
    public Response start() {
        return Response.status(Response.Status.FOUND).location(URI.create("/")).build();
    }
}
