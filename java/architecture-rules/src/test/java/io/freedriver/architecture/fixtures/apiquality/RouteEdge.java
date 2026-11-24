package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.vertx.web.Route;
import io.vertx.ext.web.RoutingContext;

/** An HTTP edge: a method carries {@code @Route} and the class uses RoutingContext. */
public class RouteEdge {
    RoutingContext context;

    @Route
    void page() {
    }
}
