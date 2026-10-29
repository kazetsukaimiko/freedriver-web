package io.freedriver.dispatcher;

/**
 * OAuth protected-resource metadata for the MCP endpoint.
 * The document is not served at {@code /mcp} or {@code /dispatch/mcp}.
 * Caddy already proxies those paths.
 */
public final class McpProtectedResource {

    public static final String MCP_PATH = "/mcp";

    public static final String DISPATCH_MCP_PATH = "/dispatch/mcp";

    public static final String PATH = "/api/dispatcher/oauth-protected-resource";

    public static final String RESOURCE = "https://app.freedriver.io/dispatch/mcp";

    public static final String METADATA_URL = "https://app.freedriver.io" + PATH;

    public static final String AUTHORIZATION_SERVER = "https://auth.freedriver.io/realms/freedriver";

    public static final String CHALLENGE = "Bearer resource_metadata=\"" + METADATA_URL + "\"";

    private McpProtectedResource() {
    }

    public static boolean isMcpPath(String path) {
        return MCP_PATH.equals(path)
                || DISPATCH_MCP_PATH.equals(path)
                || "mcp".equals(path)
                || "dispatch/mcp".equals(path);
    }
}
