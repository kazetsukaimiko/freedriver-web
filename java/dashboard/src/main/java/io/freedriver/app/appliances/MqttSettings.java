package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttEndpoint;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

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

    @WithName("trust-certificate")
    @WithDefault("/opt/freedriver-secrets/mqtt-internal/ca.crt")
    String trustCertificate();

    Optional<String> password();

    /** Host file. Used only when {@link #password()} is blank. */
    @WithName("password-file")
    Optional<String> passwordFile();

    /**
     * Comma-separated house ids for dev and test. {@link InstanceSubscriptions} keeps them subscribed
     * beside the houses in Keycloak.
     */
    @WithName("instance-ids")
    Optional<String> instanceIds();

    /** Subscribes the houses every 60 seconds and after this replica provisions one. */
    @WithDefault("true")
    boolean refresh();

    default MqttEndpoint endpoint() {
        return new MqttEndpoint(host(), port(), tls(), username());
    }

    /** The parsed {@link #instanceIds()}; empty when unset. */
    default List<UUID> configuredInstances() {
        return MqttHouseIds.parseList(instanceIds().orElse(""));
    }
}
