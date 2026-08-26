package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;

@ApplicationScoped
public class AppliancesConfig {

    @ConfigProperty(name = "freedriver.appliances.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "freedriver.appliances.live-commands", defaultValue = "false")
    boolean liveCommands;

    @ConfigProperty(name = "freedriver.appliances.backend", defaultValue = "none")
    String backend;

    @ConfigProperty(name = "freedriver.appliances.stale-after", defaultValue = "20s")
    Duration staleAfter;

    @ConfigProperty(name = "freedriver.appliances.command-timeout", defaultValue = "5s")
    Duration commandTimeout;

    @ConfigProperty(name = "freedriver.appliances.command-timeout-max", defaultValue = "30s")
    Duration commandTimeoutMax;

    @ConfigProperty(name = "freedriver.appliances.mock-refresh", defaultValue = "false")
    boolean mockRefresh;

    @ConfigProperty(name = "freedriver.appliances.rate-limit.permits", defaultValue = "30")
    int rateLimitPermits;

    @ConfigProperty(name = "freedriver.appliances.rate-limit.window", defaultValue = "60s")
    Duration rateLimitWindow;

    public boolean enabled() {
        return enabled;
    }

    public boolean liveCommands() {
        return liveCommands;
    }

    public String backend() {
        return backend;
    }

    public Duration staleAfter() {
        return staleAfter;
    }

    public Duration commandTimeout() {
        return commandTimeout;
    }

    public Duration commandTimeoutMax() {
        return commandTimeoutMax;
    }

    /** REST confirm wait. Not part of the MQTT contract. */
    public Duration boundedCommandTimeout() {
        Duration use = commandTimeout.isZero() || commandTimeout.isNegative()
                ? Duration.ofSeconds(5)
                : commandTimeout;
        Duration ceiling = boundedCommandCeiling();
        Duration floor = boundedCommandFloor();
        if (use.compareTo(ceiling) > 0) {
            return ceiling;
        }
        if (use.compareTo(floor) < 0) {
            return floor;
        }
        return use;
    }

    Duration boundedCommandCeiling() {
        return commandTimeoutMax.isZero() || commandTimeoutMax.isNegative()
                ? Duration.ofSeconds(30)
                : commandTimeoutMax;
    }

    Duration boundedCommandFloor() {
        return Duration.ofMillis(50);
    }

    public boolean mockRefresh() {
        return mockRefresh;
    }

    public RateLimit rateLimit() {
        return new RateLimit(rateLimitPermits, rateLimitWindow);
    }

    /** {@code mock} or {@code mock-autonomy}. Off in default/prod ({@code none}). */
    public boolean mockAutonomy() {
        String value = backend == null ? "" : backend.strip().toLowerCase();
        return "mock".equals(value) || "mock-autonomy".equals(value);
    }

    /** Any non-{@code none} adapter (mock now; mqtt later) may publish on the bus. */
    public boolean adapterEnabled() {
        if (backend == null) {
            return false;
        }
        String value = backend.strip().toLowerCase();
        return !value.isEmpty() && !"none".equals(value);
    }

    public record RateLimit(int permits, Duration window) {
    }
}
