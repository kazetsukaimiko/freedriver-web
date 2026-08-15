package io.freedriver.app.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/hello")
public class HelloResource {

    public record HelloResponse(String message, String service) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("Hello from Freedriver", "freedriver-app");
    }
}
