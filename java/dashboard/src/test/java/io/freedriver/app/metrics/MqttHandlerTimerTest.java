package io.freedriver.app.metrics;

import io.freedriver.mqtt.MqttConnection.MessageHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link MqttHandlerTimer#timed} records each call and rethrows what the handler throws (#594). */
class MqttHandlerTimerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MqttHandlerTimer timer = new MqttHandlerTimer(registry);

    @Test
    void aCallIsOneRecordingUnderTheOwnersSimpleName() {
        int[] calls = {0};
        timer.timed(MqttHandlerTimerTest.class, (topic, payload) -> calls[0]++).onMessage("t", new byte[0]);
        assertEquals(1, calls[0]);
        assertEquals(1, count("none"));
    }

    @Test
    void aThrowingHandlerIsRecordedWithItsExceptionAndRethrown() {
        IllegalStateException boom = new IllegalStateException("payload text");
        MessageHandler handler = timer.timed(MqttHandlerTimerTest.class, (topic, payload) -> {
            throw boom;
        });
        assertSame(boom, assertThrows(IllegalStateException.class, () -> handler.onMessage("t", new byte[0])));
        assertEquals(1, count("IllegalStateException"));
        assertEquals(0, count("none"));
    }

    private long count(String exception) {
        var found = registry.find(MqttHandlerTimer.HANDLER).tag("class", "MqttHandlerTimerTest")
                .tag("method", "onBrokerMessage").tag("exception", exception).timer();
        return found == null ? 0 : found.count();
    }
}
