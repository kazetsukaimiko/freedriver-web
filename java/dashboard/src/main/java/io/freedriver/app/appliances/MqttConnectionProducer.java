package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttConnection;
import io.freedriver.mqtt.paho.PahoMqttConnection;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.nio.file.Path;

@ApplicationScoped
public class MqttConnectionProducer {

    @Produces
    @ApplicationScoped
    @LookupIfProperty(name = "freedriver.appliances.live-commands", stringValue = "true")
    MqttConnection liveConnection(MqttSettings settings) {
        return new PahoMqttConnection(
                settings.endpoint(),
                settings.password().orElse(""),
                Path.of(settings.trustCertificate()));
    }
}
