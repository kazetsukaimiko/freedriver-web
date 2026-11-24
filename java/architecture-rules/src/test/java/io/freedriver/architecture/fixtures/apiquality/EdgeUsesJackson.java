package io.freedriver.architecture.fixtures.apiquality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.event.Observes;

/** An HTTP edge that also depends on Jackson. RoutingContext is allowed; ObjectMapper is refused. */
public class EdgeUsesJackson {
    RoutingContext context;
    ObjectMapper mapper;

    void register(@Observes Filters filters) {
    }
}
