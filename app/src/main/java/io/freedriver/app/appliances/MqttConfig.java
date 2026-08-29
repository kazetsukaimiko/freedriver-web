package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MqttConfig {

    @ConfigProperty(name = "freedriver.mqtt.host", defaultValue = "mosquitto")
    String host;

    @ConfigProperty(name = "freedriver.mqtt.port", defaultValue = "8883")
    int port;

    @ConfigProperty(name = "freedriver.mqtt.tls", defaultValue = "true")
    boolean tls;

    @ConfigProperty(name = "freedriver.mqtt.username", defaultValue = "api")
    String username;

    @ConfigProperty(name = "freedriver.mqtt.password")
    Optional<String> password;

    @ConfigProperty(name = "freedriver.mqtt.instance-ids")
    Optional<String> instanceIds;

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean tls() {
        return tls;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password.orElse("");
    }

    public List<UUID> instances() {
        String raw = instanceIds.orElse("").strip();
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
