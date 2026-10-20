package io.freedriver.dispatcher;

/**
 * OAuth protected-resource metadata for the MCP endpoint.
 * The document is not served at {@code /mcp}. Caddy already proxies this path.
 */
public final class McpProtectedResource {

    public static final String MCP_PATH = "/mcp";

    public static final String PATH = "/api/dispatcher/oauth-protected-resource";

    public static final String RESOURCE = "https://app.freedriver.io/mcp";

    public static final String METADATA_URL = "https://app.freedriver.io" + PATH;

    public static final String AUTHORIZATION_SERVER = "https://auth.freedriver.io/realms/freedriver";

    public static final String CHALLENGE = "Bearer resource_metadata=\"" + METADATA_URL + "\"";

    private McpProtectedResource() {
    }
}
