package io.freedriver.architecture.fixtures.layering.conflict;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.jmolecules.architecture.hexagonal.Application;
import org.jmolecules.architecture.hexagonal.PrimaryAdapter;

/** Two roles, so the Resource suffix is not a fallback. */
@Application
@PrimaryAdapter
public final class SplitResource {

    private final Response response;
    private final SecurityIdentity identity;

    public SplitResource(Response response, SecurityIdentity identity) {
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
