package io.freedriver.dispatcher;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import jakarta.validation.constraints.NotBlank;

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
