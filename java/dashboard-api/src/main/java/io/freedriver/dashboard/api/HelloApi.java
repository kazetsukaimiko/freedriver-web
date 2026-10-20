package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.HelloResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/hello")
public interface HelloApi {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    HelloResponse hello();
}
