package io.freedriver.app.security;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * The dashboard's one setting under {@code freedriver.mcp}. The autonomy MCP process maps its own
 * keys under the same prefix; the dashboard only checks the shared internal secret.
 */
@ConfigMapping(prefix = "freedriver.mcp")
public interface McpInternalConfig {

    /** {@code FREEDRIVER_MCP_INTERNAL_SECRET}. Unset or blank turns the internal MCP identity off. */
    Optional<String> internalSecret();
}
