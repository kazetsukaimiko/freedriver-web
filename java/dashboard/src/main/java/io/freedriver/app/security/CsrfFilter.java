package io.freedriver.app.security;

import io.freedriver.app.instances.InstanceList;
import io.freedriver.app.invites.InviteList;
import io.freedriver.dashboard.model.ApplianceMapResponse;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Command POST requires {@code X-CSRF-Token} matching the HttpOnly csrf cookie.
 * GET mints the token into that cookie and the map JSON.
 * Secure is {@code freedriver.csrf.cookie-secure}. The app sees plain HTTP behind the
 * TLS terminator, so the request scheme is not that flag.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION + 50)
public class CsrfFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String COOKIE = "freedriver-csrf";
    public static final String HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityIdentity identity;
    private final boolean cookieSecure;

    @Inject
    public CsrfFilter(
            SecurityIdentity identity,
            @ConfigProperty(name = "freedriver.csrf.cookie-secure", defaultValue = "false") boolean cookieSecure) {
        this.identity = identity;
        this.cookieSecure = cookieSecure;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !commandPath(path(request))) {
            return;
        }
        if (!commandCaller()) {
            return;
        }
        var cookie = request.getCookies().get(COOKIE);
        String header = request.getHeaderString(HEADER);
        if (cookie == null || !matches(cookie.getValue(), header)) {
            request.abortWith(Response.status(400)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity("{}")
                    .build());
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        if (!commandCaller() || response.getStatus() != 200) {
            return;
        }
        String path = path(request);
        if ("api/appliances".equals(path) && response.getEntity() instanceof ApplianceMapResponse map) {
            response.setEntity(new ApplianceMapResponse(map.instances(), token(request, response)));
        } else if ("api/invites".equals(path) && response.getEntity() instanceof InviteList list) {
            response.setEntity(new InviteList(list.invites(), token(request, response)));
        } else if ("api/instances".equals(path) && response.getEntity() instanceof InstanceList list) {
            response.setEntity(new InstanceList(list.instances(), token(request, response)));
        }
    }

    private String token(ContainerRequestContext request, ContainerResponseContext response) {
        var existing = request.getCookies().get(COOKIE);
        String token = existing != null && existing.getValue() != null && !existing.getValue().isBlank()
                ? existing.getValue()
                : mint();
        response.getHeaders().add("Set-Cookie", new NewCookie.Builder(COOKIE)
                .value(token)
                .path("/")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(NewCookie.SameSite.LAX)
                .build());
        return token;
    }

    private boolean commandCaller() {
        return identity != null
                && !identity.isAnonymous()
                && (identity.hasRole("dashboard") || identity.hasRole("portal-admin"));
    }

    private static String path(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    private static boolean commandPath(String path) {
        return path.startsWith("api/appliances/")
                || "api/invites".equals(path)
                || "api/instances".equals(path)
                || "api/phones/remove".equals(path)
                || (path.startsWith("api/invites/") && path.endsWith("/cancel"))
                || (path.startsWith("api/instances/") && path.endsWith("/open"));
    }

    static String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean matches(String cookie, String header) {
        if (cookie == null || header == null || cookie.isBlank() || header.isBlank()) {
            return false;
        }
        byte[] left = cookie.getBytes(StandardCharsets.UTF_8);
        byte[] right = header.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
