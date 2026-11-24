package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.oidc.OidcRedirectFilter;
import io.vertx.ext.web.RoutingContext;

/** An HTTP edge: it implements an {@code io.quarkus.oidc} interface and uses RoutingContext. */
public class OidcEdge implements OidcRedirectFilter {
    RoutingContext context;
}
