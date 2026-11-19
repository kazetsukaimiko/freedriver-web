package io.freedriver.architecture.fixtures.layering.fallback;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;

/** No hexagonal role. The Resource suffix is still the edge. */
public final class NamedResource {

    private final Response response;
    private final SecurityIdentity identity;

    public NamedResource(Response response, SecurityIdentity identity) {
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
