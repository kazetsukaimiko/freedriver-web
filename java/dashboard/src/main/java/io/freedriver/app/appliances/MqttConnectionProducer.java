package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.paho.PahoMqttConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.nio.file.Path;

@ApplicationScoped
public class MqttConnectionProducer {

    /**
     * A session exists when instance ids are set. An empty list does not open a client,
     * including the resubscribe thread. Command publishing stays behind {@code live-commands}.
     */
    @Produces
    @ApplicationScoped
    MqttConnection liveConnection(MqttSettings settings) {
        if (settings.instances().isEmpty()) {
            return new IdleMqttConnection();
        }
        String password = settings.password().orElse("").strip();
        if (password.isEmpty()) {
            password = MqttHouseIds.password(settings.passwordFile().orElse(""));
        }
        if (password.isEmpty()) {
            return new IdleMqttConnection();
        }
        return new PahoMqttConnection(
                settings.endpoint(),
                password,
                Path.of(settings.trustCertificate()));
    }

    /** No broker, no threads. Used only when no instance id is configured. */
    static final class IdleMqttConnection implements MqttConnection {
        @Override
        public void connect() {
        }

        @Override
        public void subscribe(String topic, int qos, MessageHandler handler) {
        }

        @Override
        public void publish(String topic, byte[] payload, int qos, boolean retain) {
        }

        @Override
        public void close() {
        }
    }
}
