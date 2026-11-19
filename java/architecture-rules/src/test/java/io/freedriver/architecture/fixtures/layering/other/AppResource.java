package io.freedriver.architecture.fixtures.layering.other;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.jmolecules.architecture.hexagonal.Application;

/** One non-adapter role, and a name that would be the edge by suffix. */
@Application
public final class AppResource {

    private final Response response;
    private final SecurityIdentity identity;

    public AppResource(Response response, SecurityIdentity identity) {
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
