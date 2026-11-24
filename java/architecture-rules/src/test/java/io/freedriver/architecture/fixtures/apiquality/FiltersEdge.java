package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

/** An HTTP edge: it observes Filters and uses RoutingContext. */
public class FiltersEdge {
    RoutingContext context;

    void register(@Observes Filters filters) {
    }
}
