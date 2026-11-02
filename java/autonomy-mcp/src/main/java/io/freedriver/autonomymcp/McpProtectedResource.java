package io.freedriver.autonomymcp;

/**
 * OAuth protected-resource metadata for the appliance MCP endpoint.
 * The document is not served at {@code /autonomy/mcp}.
 */
public final class McpProtectedResource {

    public static final String MCP_PATH = "/autonomy/mcp";

    public static final String PATH = "/api/autonomy/oauth-protected-resource";

    public static final String RESOURCE = "https://app.freedriver.io/autonomy/mcp";

    public static final String METADATA_URL = "https://app.freedriver.io" + PATH;

    public static final String AUTHORIZATION_SERVER = "https://auth.freedriver.io/realms/freedriver";

    public static final String CHALLENGE = "Bearer resource_metadata=\"" + METADATA_URL + "\"";

    public static final String CLIENT_ID = "freedriver-grok-mcp";

    private McpProtectedResource() {
    }

    public static boolean isMcpPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return MCP_PATH.equals(normalized) || normalized.startsWith(MCP_PATH + "/");
    }
}
