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
     * Dynamic-security admin username. Not {@code admin}. No default in the main profile.
     * Environment: {@code FREEDRIVER_MQTT_DYNSEC_USERNAME}.
     */
    @WithName("dynsec-username")
    Optional<String> dynsecUsername();

    /**
     * Dynamic-security admin secret. Server configuration only.
     * Environment: {@code FREEDRIVER_MQTT_DYNSEC_PASSWORD}. Never returned with a house.
     */
    @WithName("dynsec-password")
    Optional<String> dynsecPassword();

    /** Host file. Used only when {@link #dynsecPassword()} is blank. The file is not in git. */
    @WithName("dynsec-password-file")
    Optional<String> dynsecPasswordFile();

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
