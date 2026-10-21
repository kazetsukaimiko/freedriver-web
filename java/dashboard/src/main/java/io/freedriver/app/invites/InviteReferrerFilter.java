package io.freedriver.app.invites;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** The invite page does not send the fragment or the path in a Referer header. */
@ApplicationScoped
public class InviteReferrerFilter {

    public void register(@Observes Filters filters) {
        filters.register(InviteReferrerFilter::header, 20);
    }

    static void header(RoutingContext context) {
        String path = context.request().path();
        if ("/invite".equals(path) || path.startsWith("/invite/")) {
            context.response().putHeader("Referrer-Policy", "no-referrer");
        }
        context.next();
    }
}
