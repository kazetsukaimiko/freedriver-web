package io.freedriver.app.security;

/** Reads the {@code Authorization} header for a Bearer token. */
final class BearerHeaders {

    private BearerHeaders() {
    }

    /** True for {@code Bearer <token>} with a non-blank token; the scheme is case-insensitive. */
    static boolean hasBearer(String authorization) {
        if (authorization == null) {
            return false;
        }
        String value = authorization.trim();
        return value.length() > 7
                && value.regionMatches(true, 0, "Bearer ", 0, 7)
                && !value.substring(7).isBlank();
    }
}
