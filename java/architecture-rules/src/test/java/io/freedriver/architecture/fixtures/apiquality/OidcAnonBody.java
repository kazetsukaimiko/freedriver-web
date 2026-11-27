package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.oidc.OidcRedirectFilter;
import io.vertx.ext.web.RoutingContext;

/** An HTTP edge whose vert.x type is used only inside an anonymous class. */
public class OidcAnonBody implements OidcRedirectFilter {
    void hidden() {
        new Runnable() {
            RoutingContext routing;

            public void run() {
            }
        }.run();
    }
}
