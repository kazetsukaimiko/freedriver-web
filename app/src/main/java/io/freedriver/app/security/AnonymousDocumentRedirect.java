package io.freedriver.app.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * When OIDC is on, anonymous full documents go to {@code /login} (Keycloak start).
 * XHR keeps 401 on the API. No-op while {@code quarkus.oidc.enabled} is false so
 * the house site and quarkus:dev still load without Keycloak.
 */
@ApplicationScoped
public class AnonymousDocumentRedirect {

    private final boolean oidcEnabled;

    @Inject
    public AnonymousDocumentRedirect(
            @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "false") boolean oidcEnabled) {
        this.oidcEnabled = oidcEnabled;
    }

    void register(@Observes Filters filters) {
        filters.register(this::filter, 150);
    }

    void filter(RoutingContext rc) {
        if (!shouldRedirect(
                oidcEnabled,
                rc.request().method().name(),
                rc.request().path(),
                rc.request().getHeader("X-Requested-With"),
                anonymous(rc))) {
            rc.next();
            return;
        }
        rc.response()
                .setStatusCode(302)
                .putHeader("Location", "/login")
                .end();
    }

    static boolean shouldRedirect(
            boolean oidcEnabled, String method, String path, String requestedWith, boolean anonymous) {
        if (!oidcEnabled || !anonymous) {
            return false;
        }
        if (method == null || (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))) {
            return false;
        }
        if (requestedWith != null && !requestedWith.isBlank()) {
            return false;
        }
        return isSpaDocument(path);
    }

    static boolean isSpaDocument(String path) {
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if ("/api".equals(path) || path.startsWith("/api/")
                || "/q".equals(path) || path.startsWith("/q/")
                || path.startsWith("/assets/")
                || "/login".equals(path)
                || "/favicon.png".equals(path)
                || "/favicon.svg".equals(path)
                || "/favicon.ico".equals(path)) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.isEmpty() || last.indexOf('.') < 0 || last.endsWith(".html");
    }

    private static boolean anonymous(RoutingContext rc) {
        if (rc.user() instanceof QuarkusHttpUser user) {
            var identity = user.getSecurityIdentity();
            return identity == null || identity.isAnonymous();
        }
        return rc.user() == null;
    }
}
