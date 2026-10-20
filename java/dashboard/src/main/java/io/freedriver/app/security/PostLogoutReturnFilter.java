package io.freedriver.app.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * On the post-logout path, drops {@code q_post_logout} and rejects a return whose
 * {@code state} does not match that cookie. Quarkus sets the cookie and does not check it.
 */
@ApplicationScoped
public class PostLogoutReturnFilter {

    static final int PRIORITY = 400;

    private final boolean forceSecure;
    private final String cookiePath;

    @Inject
    public PostLogoutReturnFilter(
            @ConfigProperty(name = "quarkus.oidc.authentication.cookie-force-secure", defaultValue = "false")
                    boolean forceSecure,
            @ConfigProperty(name = "quarkus.oidc.authentication.cookie-path", defaultValue = "/") String cookiePath) {
        this.forceSecure = forceSecure;
        this.cookiePath = cookiePath;
    }

    void register(@Observes Filters filters) {
        filters.register(this::filter, PRIORITY);
    }

    void filter(RoutingContext rc) {
        String cookie = cookieValue(rc);
        PostLogoutReturn.Action action =
                PostLogoutReturn.decide(rc.request().path(), rc.request().params().getAll("state"), cookie);
        if (action == PostLogoutReturn.Action.CONTINUE) {
            rc.next();
            return;
        }
        clearCookie(rc);
        if (action == PostLogoutReturn.Action.REJECT) {
            rc.response().setStatusCode(400).end("Sign-out could not be confirmed.");
            return;
        }
        rc.next();
    }

    private void clearCookie(RoutingContext rc) {
        Cookie cleared = Cookie.cookie(PostLogoutReturn.COOKIE, "");
        cleared.setPath(cookiePath);
        cleared.setMaxAge(0);
        cleared.setHttpOnly(true);
        cleared.setSecure(forceSecure || rc.request().isSSL());
        rc.response().addCookie(cleared);
    }

    private static String cookieValue(RoutingContext rc) {
        Cookie cookie = rc.request().getCookie(PostLogoutReturn.COOKIE);
        return cookie == null ? null : cookie.getValue();
    }
}
