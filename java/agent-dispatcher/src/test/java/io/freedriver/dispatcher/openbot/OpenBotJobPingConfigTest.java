package io.freedriver.dispatcher.openbot;

import io.freedriver.dispatcher.DispatcherConfig;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.validator.BeanValidationConfigValidator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The OpenBot ping reads {@link DispatcherConfig}'s webhook address. */
class OpenBotJobPingConfigTest {

    private static final String WEBHOOK_URL = "openbot-webhook-url";

    private static final URI COMPILED = URI.create(
            "https://signal.openbot.run/v1/webhooks/0ca403fc-5874-407c-8e70-38c518d442ee");

    @Test
    void the_ping_resolves_the_compiled_address_when_the_webhook_url_is_absent() {
        DispatcherConfig config = mapping(Map.of());

        assertFalse(config.openbotWebhookUrl().isPresent());
        assertEquals(COMPILED, new OpenBotJobPing(config).url());
    }

    @Test
    void the_ping_resolves_the_compiled_address_when_the_webhook_url_is_empty() {
        DispatcherConfig config = mapping(Map.of(WEBHOOK_URL, ""));

        assertFalse(config.openbotWebhookUrl().isPresent());
        assertEquals(COMPILED, new OpenBotJobPing(config).url());
    }

    @Test
    void the_ping_resolves_the_compiled_address_when_the_webhook_url_is_white_space() {
        DispatcherConfig config = mapping(Map.of(WEBHOOK_URL, " \t "));

        assertFalse(config.openbotWebhookUrl().isPresent());
        assertEquals(COMPILED, new OpenBotJobPing(config).url());
    }

    @Test
    void the_ping_is_built_with_a_configured_https_webhook_url() {
        String relay = "https://signal.openbot.run/v1/webhooks/relay-agent";

        assertEquals(URI.create(relay), new OpenBotJobPing(mapping(Map.of(WEBHOOK_URL, relay))).url());
        assertEquals(URI.create(relay), new OpenBotJobPing(mapping(Map.of(WEBHOOK_URL, " " + relay + " "))).url());
    }

    private static DispatcherConfig mapping(Map<String, String> values) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("freedriver.dispatcher.client.dispatcher", "freedriver-dispatcher");
        properties.put("freedriver.dispatcher.client.device", "freedriver-openbot");
        properties.put("freedriver.dispatcher.client.portal", "freedriver-portal-jobs");
        values.forEach((key, value) -> properties.put("freedriver.dispatcher." + key, value));
        Validator validator = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 200, new DurationConverter())
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .withMapping(DispatcherConfig.class)
                .withValidator((BeanValidationConfigValidator) () -> validator)
                .build();
        return config.getConfigMapping(DispatcherConfig.class);
    }
}
