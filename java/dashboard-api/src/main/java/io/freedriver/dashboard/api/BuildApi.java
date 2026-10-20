package io.freedriver.dashboard.api;

import io.freedriver.dashboard.model.BuildResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/build")
public interface BuildApi {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    BuildResponse build();
}
