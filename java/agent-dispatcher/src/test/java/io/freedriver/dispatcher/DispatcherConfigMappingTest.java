package io.freedriver.dispatcher;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.ConfigValidationException;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.validator.BeanValidationConfigValidator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DispatcherConfig} with Bean Validation, as start-up builds it. */
class DispatcherConfigMappingTest {

    private static final String WEBHOOK_URL = "openbot-webhook-url";

    @Test
    void the_host_lease_and_take_window_default_to_ninety_seconds_and_ten_minutes() {
        DispatcherConfig config = mapping(Map.of());

        assertEquals(Duration.ofSeconds(90), config.hostLease());
        assertEquals(Duration.ofMinutes(10), config.takeWithin());
    }

    @Test
    void start_up_fails_when_the_host_lease_is_not_longer_than_fifty_seconds() {
        ConfigValidationException refused = assertThrows(ConfigValidationException.class,
                () -> mapping(Map.of("host-lease", "50s")));
        assertTrue(refused.getMessage().contains("host-lease"), refused.getMessage());

        assertEquals(Duration.ofSeconds(51), mapping(Map.of("host-lease", "51s")).hostLease());
    }

    @Test
    void start_up_fails_when_the_take_window_is_under_one_second() {
        for (String value : new String[] {"0s", "-1s", "999ms"}) {
            ConfigValidationException refused = assertThrows(ConfigValidationException.class,
                    () -> mapping(Map.of("take-within", value)), value);
            assertTrue(refused.getMessage().contains("take-within"), refused.getMessage());
        }

        assertEquals(Duration.ofSeconds(1), mapping(Map.of("take-within", "1s")).takeWithin());
    }

    @Test
    void the_host_silence_window_defaults_to_ten_minutes_and_must_be_longer_than_fifty_seconds() {
        assertEquals(Duration.ofMinutes(10), mapping(Map.of()).hostSilentAfter());

        ConfigValidationException refused = assertThrows(ConfigValidationException.class,
                () -> mapping(Map.of("host-silent-after", "50s")));
        assertTrue(refused.getMessage().contains("host-silent-after"), refused.getMessage());

        assertEquals(Duration.ofSeconds(51), mapping(Map.of("host-silent-after", "51s")).hostSilentAfter());
    }

    @Test
    void start_up_fails_when_the_webhook_url_is_not_an_https_address_with_a_host_and_without_user_info() {
        String[] refusedValues = {
            "not a uri",
            "https:///v1/webhooks/relay-agent",
            "https://user:secret@signal.openbot.run/v1/webhooks/relay-agent",
            "http://example.com/hook",
            "http://127.0.0.1:8099/hook",
            "http://localhost",
        };
        for (String value : refusedValues) {
            ConfigValidationException refused = assertThrows(ConfigValidationException.class,
                    () -> mapping(Map.of(WEBHOOK_URL, value)), value);
            assertTrue(refused.getMessage().contains("freedriver.dispatcher.openbot-webhook-url"),
                    value + ": " + refused.getMessage());
        }
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
