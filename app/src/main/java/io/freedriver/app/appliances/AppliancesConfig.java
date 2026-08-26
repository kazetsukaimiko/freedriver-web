package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;

@ApplicationScoped
public class AppliancesConfig {

    @ConfigProperty(name = "freedriver.appliances.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "freedriver.appliances.live-commands", defaultValue = "false")
    boolean liveCommands;

    @ConfigProperty(name = "freedriver.appliances.backend", defaultValue = "none")
    String backend;

    @ConfigProperty(name = "freedriver.appliances.auth-required", defaultValue = "true")
    boolean authRequired;

    @ConfigProperty(name = "freedriver.appliances.csrf", defaultValue = "true")
    boolean csrf;

    @ConfigProperty(name = "freedriver.appliances.stale-after", defaultValue = "20s")
    Duration staleAfter;

    @ConfigProperty(name = "freedriver.appliances.command-timeout", defaultValue = "5s")
    Duration commandTimeout;

    @ConfigProperty(name = "freedriver.appliances.command-timeout-max", defaultValue = "30s")
    Duration commandTimeoutMax;

    @ConfigProperty(name = "freedriver.appliances.fake-refresh", defaultValue = "false")
    boolean fakeRefresh;

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

    public boolean authRequired() {
        return authRequired;
    }

    public boolean csrf() {
        return csrf;
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
        Duration use = Optional.ofNullable(commandTimeout)
                .filter(Predicate.not(Duration::isZero))
                .filter(Predicate.not(Duration::isNegative))
                .orElseGet(() -> Duration.ofSeconds(5));
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
        return Optional.ofNullable(commandTimeoutMax)
                .filter(Predicate.not(Duration::isZero))
                .filter(Predicate.not(Duration::isNegative))
                .orElseGet(() -> Duration.ofSeconds(30));
    }

    Duration boundedCommandFloor() {
        return Duration.ofMillis(50);
    }

    public boolean fakeRefresh() {
        return fakeRefresh;
    }

    public RateLimit rateLimit() {
        return new RateLimit(rateLimitPermits, rateLimitWindow);
    }

    public record RateLimit(int permits, Duration window) {
    }
}
