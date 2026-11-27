package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.oidc.OidcRedirectFilter;
import io.vertx.ext.web.RoutingContext;

/**
 * The anonymous class implements an OIDC filter. The holding class does not, so the vert.x type
 * stays refused.
 */
public class PlainAnonOidc {
    void run() {
        new OidcRedirectFilter() {
            RoutingContext routing;
        };
    }
}
