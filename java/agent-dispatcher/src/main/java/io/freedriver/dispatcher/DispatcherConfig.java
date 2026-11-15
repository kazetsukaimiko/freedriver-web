package io.freedriver.dispatcher;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.time.DurationMin;

import java.time.Duration;
import java.util.Optional;

/** Settings of the dispatcher under {@code freedriver.dispatcher}. */
@ConfigMapping(prefix = "freedriver.dispatcher")
public interface DispatcherConfig {

    /** The OIDC client ids {@link AllowedClientFilter} pairs with each role. */
    Client client();

    /** How long a submit waits for a device to take the job. */
    @WithDefault("75s")
    Duration submitTimeout();

    /**
     * How long a host holds a job it took or started. It is longer than the longest
     * take wait (50 seconds), so a host that renews on every take keeps its jobs.
     */
    @DurationMin(seconds = 50, inclusive = false)
    @WithDefault("90s")
    Duration hostLease();

    /** A queued job no host takes within this time becomes {@code EXPIRED}. At least one second. */
    @DurationMin(seconds = 1)
    @WithDefault("10m")
    Duration takeWithin();

    /** {@code FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET}. Unset or blank skips the OpenBot ping. */
    Optional<String> openbotWebhookSecret();

    interface Client {

        @NotBlank
        String dispatcher();

        @NotBlank
        String device();

        @NotBlank
        String portal();
    }
}
