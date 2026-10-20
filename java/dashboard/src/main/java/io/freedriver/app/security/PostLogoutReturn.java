package io.freedriver.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Checks the Quarkus {@code q_post_logout} cookie against the {@code state}
 * Keycloak sends back to {@code quarkus.oidc.logout.post-logout-path}.
 */
public final class PostLogoutReturn {

    public static final String COOKIE = "q_post_logout";
    static final String PATH = "/";
    static final int MAX_TOKEN_LENGTH = 128;

    public enum Action {
        CONTINUE,
        CLEAR,
        REJECT
    }

    private PostLogoutReturn() {}

    public static Action decide(String path, List<String> states, String cookie) {
        if (!PATH.equals(path)) {
            return Action.CONTINUE;
        }
        if (cookie == null || cookie.isEmpty()) {
            return Action.CONTINUE;
        }
        if (cookie.length() > MAX_TOKEN_LENGTH) {
            return Action.REJECT;
        }
        if (states == null || states.isEmpty()) {
            return Action.CLEAR;
        }
        if (states.size() != 1) {
            return Action.REJECT;
        }
        String state = states.get(0);
        if (state == null || state.isEmpty() || state.length() > MAX_TOKEN_LENGTH
                || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            return Action.REJECT;
        }
        return Action.CLEAR;
    }
}
