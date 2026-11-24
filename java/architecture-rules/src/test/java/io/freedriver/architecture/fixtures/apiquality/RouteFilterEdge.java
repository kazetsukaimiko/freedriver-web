package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

/** An HTTP edge: a method carries {@code @RouteFilter} and the class uses RoutingContext. */
public class RouteFilterEdge {
    RoutingContext context;

    @RouteFilter
    void filter() {
    }
}
