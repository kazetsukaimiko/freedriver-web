package io.freedriver.app.metrics;

import io.freedriver.app.appliances.MqttLiveClient;
import io.freedriver.app.changelog.Changelog;
import io.freedriver.app.instances.KeycloakInstanceIdentity;
import io.freedriver.app.phones.HttpSmsPhoneList;
import io.freedriver.app.phones.KeycloakPhoneSignIn;
import io.micrometer.core.annotation.Timed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The six blocking-IO beans carry a class-level {@code @Timed} that sets only the timer name. */
class IoTimerAnnotationTest {
    private static final List<Class<?>> TIMED = List.of(
            MqttLiveClient.class,
            Changelog.class,
            KeycloakInstanceIdentity.class,
            MetricsMqttForwarder.class,
            HttpSmsPhoneList.class,
            KeycloakPhoneSignIn.class);

    @Test
    void timerNameIsFreedriverIoBlocking() {
        assertEquals("freedriver.io.blocking", IoTimer.BLOCKING);
    }

    @Test
    void sixBeansSetOnlyTheTimerName() {
        for (Class<?> type : TIMED) {
            Timed timed = type.getDeclaredAnnotation(Timed.class);
            assertNotNull(timed, type.getName());
            assertEquals(IoTimer.BLOCKING, timed.value(), type.getName());
            assertArrayEquals(new String[0], timed.extraTags(), type.getName());
            assertArrayEquals(new double[0], timed.percentiles(), type.getName());
            assertFalse(timed.histogram(), type.getName());
            assertFalse(timed.longTask(), type.getName());
            assertEquals("", timed.description(), type.getName());
            for (Method method : type.getDeclaredMethods()) {
                assertNull(method.getDeclaredAnnotation(Timed.class), type.getName() + "#" + method.getName());
            }
        }
    }

    @Test
    void emitterCarriesNoTimed() {
        assertNull(GrafanaMetricsEmitter.class.getAnnotation(Timed.class));
        for (Method method : GrafanaMetricsEmitter.class.getDeclaredMethods()) {
            assertNull(method.getDeclaredAnnotation(Timed.class), method.getName());
        }
    }
}
