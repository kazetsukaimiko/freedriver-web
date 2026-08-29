package io.freedriver.app.appliances;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class MqttConnectionProducer {

    @Produces
    @ApplicationScoped
    @LookupIfProperty(name = "freedriver.appliances.live-commands", stringValue = "true")
    MqttConnection liveConnection(MqttConfig config) {
        return new PahoMqttConnection(config);
    }
}
