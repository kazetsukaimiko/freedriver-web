package io.freedriver.app.metrics;

import io.freedriver.mqtt.MqttConnection.MessageHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Times the handlers the MQTT broker client calls, one recording per delivered message (#594). A
 * handler runs on the broker client's callback thread, so its time is how long that thread was
 * held, which is a different measure from {@link IoTimer#BLOCKING} and has its own meter.
 *
 * <p>A handler is passed to {@code subscribe} as {@code this::onBrokerMessage}, which does not go
 * through the bean's proxy, so an interceptor annotation cannot time it; {@link #timed} wraps it at
 * the {@code subscribe} call instead. Tags: {@code class} (the owner's simple name), {@code method}
 * ({@code onBrokerMessage}) and {@code exception} ({@code none} or the exception's simple class
 * name). Never the topic, the payload or an instance id. Prometheus exports
 * {@code freedriver_mqtt_handler_seconds_count}, {@code _sum} and {@code _max}.
 */
@ApplicationScoped
public class MqttHandlerTimer {
    public static final String HANDLER = "freedriver.mqtt.handler";
    static final String METHOD = "onBrokerMessage";

    private final MeterRegistry registry;

    @Inject
    public MqttHandlerTimer(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@code handler}, recording each call under {@link #HANDLER}; a thrown exception is recorded and rethrown. */
    public MessageHandler timed(Class<?> owner, MessageHandler handler) {
        String type = owner.getSimpleName();
        return (topic, payload) -> {
            Timer.Sample sample = Timer.start(registry);
            String exception = "none";
            try {
                handler.onMessage(topic, payload);
            } catch (RuntimeException | Error e) {
                exception = e.getClass().getSimpleName();
                throw e;
            } finally {
                sample.stop(Timer.builder(HANDLER)
                        .tag("class", type)
                        .tag("method", METHOD)
                        .tag("exception", exception)
                        .register(registry));
            }
        };
    }
}
