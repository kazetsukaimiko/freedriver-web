package io.freedriver.architecture.fixtures.layering.fallback;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** No hexagonal role and no edge suffix. {@code @Provider} is still the edge. */
@Provider
public final class Marked {

    private final Response response;
    private final SecurityIdentity identity;

    public Marked(Response response, SecurityIdentity identity) {
        this.response = response;
        this.identity = identity;
    }

    public Response response() {
        return response;
    }

    public SecurityIdentity identity() {
        return identity;
    }
}
