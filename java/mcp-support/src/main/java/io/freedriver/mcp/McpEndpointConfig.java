package io.freedriver.mcp;

import io.smallrye.config.ConfigMapping;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Optional;

/**
 * The {@code freedriver.mcp} keys this library reads in every service that serves MCP: the
 * endpoint {@link McpEndpoint} describes and the Redis prefix of {@link RedisSessionRecords}.
 * A service maps its own extra keys under the same prefix in its own interface.
 */
@ConfigMapping(prefix = "freedriver.mcp")
public interface McpEndpointConfig {

    /** The resource the access token is for, for example {@code https://app.freedriver.io/autonomy/mcp}. */
    @NotBlank
    String resource();

    @NotEmpty
    List<@NotBlank String> authorizationServers();

    /** The path of the metadata document on this service, for example {@code /api/autonomy/oauth-protected-resource}. */
    @NotBlank
    String metadataPath();

    @NotEmpty
    List<@NotBlank String> paths();

    /** Roles that may use the MCP paths; unset means any signed-in caller. */
    Optional<List<String>> roles();

    /** Redis key prefix of the MCP session bindings, for example {@code freedriver:mcp:autonomy:}. */
    @NotBlank
    String sessionPrefix();
}
