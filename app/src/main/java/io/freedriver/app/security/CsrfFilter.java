package io.freedriver.app.security;

import io.freedriver.app.appliances.ApplianceMapResponse;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Command POST requires {@code X-CSRF-Token} matching the HttpOnly csrf cookie.
 * GET mints the token into that cookie and the map JSON. Session cookie is unchanged.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHORIZATION + 50)
public class CsrfFilter implements ContainerRequestFilter, ContainerResponseFilter {

    public static final String COOKIE = "freedriver-csrf";
    public static final String HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityIdentity identity;

    @Inject
    public CsrfFilter(SecurityIdentity identity) {
        this.identity = identity;
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
        if (!"GET".equalsIgnoreCase(request.getMethod()) || !mapPath(path(request))) {
            return;
        }
        if (!commandCaller()) {
            return;
        }
        if (response.getStatus() != 200 || !(response.getEntity() instanceof ApplianceMapResponse map)) {
            return;
        }
        var existing = request.getCookies().get(COOKIE);
        String token = existing != null && existing.getValue() != null && !existing.getValue().isBlank()
                ? existing.getValue()
                : mint();
        boolean secure = "https".equalsIgnoreCase(request.getUriInfo().getRequestUri().getScheme());
        response.getHeaders().add("Set-Cookie", new NewCookie.Builder(COOKIE)
                .value(token)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite(NewCookie.SameSite.LAX)
                .build());
        response.setEntity(new ApplianceMapResponse(map.instances(), token));
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

    private static boolean mapPath(String path) {
        return "api/appliances".equals(path);
    }

    private static boolean commandPath(String path) {
        return path.startsWith("api/appliances/");
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
