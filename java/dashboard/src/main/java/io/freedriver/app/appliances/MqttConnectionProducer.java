package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.paho.PahoMqttConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.nio.file.Path;

@ApplicationScoped
public class MqttConnectionProducer {

    /**
     * The Paho session when the api password is set. {@link MqttLiveClient} connects it when the
     * first house is subscribed, so no house means no client thread. Command publishing stays
     * behind {@code live-commands}.
     */
    @Produces
    @ApplicationScoped
    MqttConnection liveConnection(MqttSettings settings) {
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

    /** No broker, no threads. Used when the api password is not set. */
    static final class IdleMqttConnection implements MqttConnection {
        @Override
        public void connect() {
        }

        @Override
        public void subscribe(String topic, int qos, MessageHandler handler) {
        }

        @Override
        public void unsubscribe(String topic) {
        }

        @Override
        public boolean connected() {
            return false;
        }

        @Override
        public void publish(String topic, byte[] payload, int qos, boolean retain) {
        }

        @Override
        public void close() {
        }
    }
}
