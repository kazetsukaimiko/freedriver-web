package io.freedriver.architecture.fixtures.apiquality;

import jakarta.ws.rs.Path;

/** A functional JAX-RS contract. The annotation's package is jakarta.ws.rs. */
@Path("/one")
public interface OneApi {
    String read();
}
