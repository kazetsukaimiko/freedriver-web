package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigProducerUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A blank SMS shared secret must not stop the dashboard from booting. */
class HttpSmsPhoneListConfigTest {

    @Test
    void empty_shared_secret_stays_blank_and_calls_fail_closed() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(Map.of("sms.otp.shared-secret", ""), "env", 300))
                .build();
        assertThrows(RuntimeException.class, () -> ConfigProducerUtil.getValue(
                "sms.otp.shared-secret", String.class, "", config));
        Optional<String> secret = ConfigProducerUtil.getValue(
                "sms.otp.shared-secret", optionalString(), null, config);
        assertTrue(secret.isEmpty() || secret.get().isBlank());

        HttpSmsPhoneList phones = new HttpSmsPhoneList(null, null, "http://sms:8080", secret.orElse(""));
        InviteUpstreamException error = assertThrows(InviteUpstreamException.class, phones::list);
        assertEquals("sms shared secret is unset", error.getMessage());
    }

    @Test
    void missing_shared_secret_is_blank() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors().build();
        ConfigValue value = config.getConfigValue("sms.otp.shared-secret");
        assertTrue(value.getValue() == null);
        Optional<String> secret = ConfigProducerUtil.getValue(
                "sms.otp.shared-secret", optionalString(), null, config);
        assertTrue(secret.isEmpty());
    }

    private static Type optionalString() {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {String.class};
            }

            @Override
            public Type getRawType() {
                return Optional.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }
}
