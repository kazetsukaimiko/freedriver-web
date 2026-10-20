package io.freedriver.app.api;

import io.freedriver.dashboard.model.HelloResponse;
import io.freedriver.dashboard.api.HelloApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Path;

@ApplicationScoped
@Path("/api/hello")
public class HelloResource implements HelloApi {

    @Override
    public HelloResponse hello() {
        return new HelloResponse("Hello from Freedriver", "freedriver-app");
    }
}
