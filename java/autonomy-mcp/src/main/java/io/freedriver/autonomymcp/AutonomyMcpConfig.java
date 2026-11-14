package io.freedriver.autonomymcp;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;
import java.util.Optional;

/**
 * This service's own {@code freedriver.mcp} keys. The endpoint and session keys under the same
 * prefix are {@link io.freedriver.mcp.McpEndpointConfig} in mcp-support.
 */
@ConfigMapping(prefix = "freedriver.mcp")
public interface AutonomyMcpConfig {

    /** Off answers 404 on the MCP paths. */
    @WithDefault("false")
    boolean enabled();

    /**
     * The dashboard base URL. Only {@code quarkus.rest-client.dashboard.url} reads it, through an
     * expression in application.properties.
     */
    @NotBlank
    String dashboardUrl();

    /** {@code FREEDRIVER_MCP_INTERNAL_SECRET}, sent on every dashboard call. */
    Optional<String> internalSecret();

    /** Calls per caller per tool. */
    ToolRateLimit toolRateLimit();

    interface ToolRateLimit {

        @Min(1)
        @WithDefault("30")
        int permits();

        @DurationMin(millis = 1)
        @WithDefault("60s")
        Duration window();
    }
}
