package io.freedriver.app.appliances;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.Min;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;

/** Settings of the appliances feature under {@code freedriver.appliances}. */
@ConfigMapping(prefix = "freedriver.appliances")
public interface AppliancesConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("false")
    boolean liveCommands();

    /** Turns on the {@link MockAutonomy} event source on the shared CDI bus. */
    @WithDefault("false")
    boolean mock();

    @WithDefault("20s")
    Duration staleAfter();

    /** Not validated: {@link #boundedCommandTimeout()} turns zero or negative into 5 s. */
    @WithDefault("5s")
    Duration commandTimeout();

    /** Not validated: {@link #boundedCommandCeiling()} turns zero or negative into 30 s. */
    @WithDefault("30s")
    Duration commandTimeoutMax();

    @WithDefault("false")
    boolean mockRefresh();

    /** The per-user appliance command limit. auth-policy.properties sets and locks both values. */
    RateLimit rateLimit();

    /** REST-side confirm wait for one command, clamped between a 50 ms floor and {@code command-timeout-max}. */
    default Duration boundedCommandTimeout() {
        Duration timeout = commandTimeout();
        Duration use = timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
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

    default Duration boundedCommandCeiling() {
        Duration max = commandTimeoutMax();
        return max.isZero() || max.isNegative()
                ? Duration.ofSeconds(30)
                : max;
    }

    default Duration boundedCommandFloor() {
        return Duration.ofMillis(50);
    }

    interface RateLimit {

        @Min(1)
        @WithDefault("30")
        int permits();

        @DurationMin(millis = 1)
        @WithDefault("60s")
        Duration window();
    }
}
