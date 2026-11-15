package io.freedriver.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;

/**
 * One service's MCP endpoint: its paths, the OAuth protected resource it is, where that resource's
 * metadata document is served, and which roles may open a session.
 */
@ApplicationScoped
public class McpEndpoint {

    /** The Streamable HTTP session header. Header names are case-insensitive. */
    public static final String SESSION_HEADER = "Mcp-Session-Id";

    private final String resource;
    private final List<String> authorizationServers;
    private final String metadataPath;
    private final List<String> paths;
    private final List<String> roles;

    @Inject
    public McpEndpoint(McpEndpointConfig config) {
        this.resource = config.resource();
        this.authorizationServers = List.copyOf(config.authorizationServers());
        this.metadataPath = config.metadataPath();
        this.paths = config.paths().stream().map(McpEndpoint::normalize).toList();
        this.roles = config.roles().map(List::copyOf).orElse(List.of());
    }

    /** The resource the access token is for, for example {@code https://app.freedriver.io/autonomy/mcp}. */
    public String resource() {
        return resource;
    }

    public List<String> authorizationServers() {
        return authorizationServers;
    }

    /** The path of the metadata document on this service, for example {@code /api/autonomy/oauth-protected-resource}. */
    public String metadataPath() {
        return metadataPath;
    }

    /** The public metadata URL: the resource's origin plus {@link #metadataPath()}. */
    public String metadataUrl() {
        URI origin = URI.create(resource);
        return origin.getScheme() + "://" + origin.getRawAuthority() + metadataPath;
    }

    /** The {@code WWW-Authenticate} value of an anonymous MCP request. */
    public String challenge() {
        return "Bearer resource_metadata=\"" + metadataUrl() + "\"";
    }

    /** Roles that may use the MCP paths; empty means any signed-in caller. */
    public List<String> roles() {
        return roles;
    }

    /** True for an MCP path or a path below one. A query and a trailing slash are ignored. */
    public boolean isMcpPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = normalize(path);
        for (String mcp : paths) {
            if (mcp.equals(normalized) || normalized.startsWith(mcp + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        int query = normalized.indexOf('?');
        if (query >= 0) {
            normalized = normalized.substring(0, query);
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
