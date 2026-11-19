package io.freedriver.architecture.fixtures.layering.secondary;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;

/** A secondary adapter by its package, with a name that matches no edge suffix. */
public final class FromPackage {

    private final Response response;
    private final SecurityIdentity identity;

    public FromPackage(Response response, SecurityIdentity identity) {
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
