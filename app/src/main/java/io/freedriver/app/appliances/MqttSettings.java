package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttEndpoint;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ConfigMapping(prefix = "freedriver.mqtt")
public interface MqttSettings {

    @WithDefault("mosquitto")
    String host();

    @WithDefault("8883")
    int port();

    @WithDefault("true")
    boolean tls();

    @WithDefault("api")
    String username();

    Optional<String> password();

    @WithName("instance-ids")
    Optional<String> instanceIds();

    default MqttEndpoint endpoint() {
        return new MqttEndpoint(host(), port(), tls(), username());
    }

    default List<UUID> instances() {
        String raw = instanceIds().orElse("").strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.strip();
            if (id.isEmpty()) {
                continue;
            }
            out.add(UUID.fromString(id));
        }
        return List.copyOf(out);
    }
}
