package io.freedriver.architecture.fixtures.layering.nested;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.jmolecules.architecture.hexagonal.PrimaryAdapter;

/** A primary adapter. Its nested class takes that role and matches no edge suffix. */
@PrimaryAdapter
public class Outer {

    public static final class Hold {

        private final Response response;
        private final SecurityIdentity identity;

        public Hold(Response response, SecurityIdentity identity) {
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
}
