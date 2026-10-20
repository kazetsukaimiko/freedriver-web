package io.freedriver.app.appliances;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(MqttTrustCertificateAbsentTest.MissingFile.class)
class MqttTrustCertificateAbsentTest {

    static final String MISSING = "target/missing-mqtt-internal-ca.crt";

    public static class MissingFile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "freedriver.appliances.live-commands", "false",
                    "freedriver.mqtt.host", "mosquitto",
                    "freedriver.mqtt.port", "8883",
                    "freedriver.mqtt.tls", "true",
                    "freedriver.mqtt.trust-certificate", MISSING,
                    "quarkus.http.test-port", "0");
        }
    }

    @Inject
    MqttSettings mqtt;

    @Inject
    AppliancesConfig appliances;

    @Inject
    FixtureMqttConnection connection;

    @Test
    void startup_succeeds_when_the_trust_certificate_is_absent() {
        assertFalse(Files.exists(Path.of(MISSING)));
        assertFalse(appliances.liveCommands());
        assertEquals(MISSING, mqtt.trustCertificate());
        assertEquals("mosquitto", mqtt.host());
        assertEquals(8883, mqtt.port());
        assertTrue(mqtt.tls());
        assertFalse(connection.connected());
    }
}
