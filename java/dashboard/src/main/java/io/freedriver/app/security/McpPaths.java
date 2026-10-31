package io.freedriver.app.security;

/**
 * The MCP bearer path {@code /autonomy/mcp}. Cookie session names start with
 * {@code q_session}, matching Quarkus OIDC.
 */
final class McpPaths {

    static final String PATH = "/autonomy/mcp";
    static final String AUDIENCE = "https://app.freedriver.io/autonomy/mcp";
    static final String CLIENT_ID = "freedriver-grok-mcp";

    private McpPaths() {
    }

    static boolean matches(String path) {
        String normalized = normalize(path);
        return PATH.equals(normalized) || normalized.startsWith(PATH + "/");
    }

    static boolean hasSessionCookie(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return false;
        }
        for (String part : cookieHeader.split(";")) {
            String name = part.trim();
            int eq = name.indexOf('=');
            if (eq >= 0) {
                name = name.substring(0, eq).trim();
            }
            if (name.startsWith("q_session")) {
                return true;
            }
        }
        return false;
    }

    static boolean hasBearer(String authorization) {
        if (authorization == null) {
            return false;
        }
        String value = authorization.trim();
        return value.length() > 7
                && value.regionMatches(true, 0, "Bearer ", 0, 7)
                && !value.substring(7).isBlank();
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
