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

    @WithName("instance-ids")
    Optional<String> instanceIds();

    /**
     * Host file of instance ids, or a broker ACL whose appliances topics name them.
     * Used only when {@link #instanceIds()} is blank. The id is not in git.
     */
    @WithName("instance-ids-file")
    Optional<String> instanceIdsFile();

    default MqttEndpoint endpoint() {
        return new MqttEndpoint(host(), port(), tls(), username());
    }

    default List<UUID> instances() {
        return MqttHouseIds.instances(instanceIds().orElse(""), instanceIdsFile().orElse(""));
    }
}
