package io.freedriver.architecture.fixtures.apiquality;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

/** An HTTP edge: it observes Router and uses RoutingContext. */
public class RouterEdge {
    RoutingContext context;

    void route(@Observes Router router) {
    }
}
