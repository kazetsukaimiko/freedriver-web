package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultProfileSafetyTest {

    @Test
    void default_profile_keeps_appliances_dark() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(properties.contains("freedriver.appliances.enabled=false"));
        assertTrue(properties.contains("freedriver.appliances.live-commands=false"));
        assertTrue(properties.contains("freedriver.appliances.mock=false"));
        assertFalse(properties.matches("(?s).*\\nfreedriver\\.appliances\\.enabled=true.*"));
        assertFalse(properties.contains("freedriver.appliances.backend="));
        assertFalse(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/FakeApplianceBackend.java")));
        assertFalse(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/ApplianceBackend.java")));
        assertTrue(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/MockAutonomy.java")));
        assertTrue(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/ApplianceControl.java")));
        assertTrue(properties.contains("quarkus.oidc.enabled=false"));
        assertFalse(properties.matches("(?s).*\\nquarkus\\.oidc\\.enabled=true.*"));
        assertTrue(properties.contains("freedriver.mqtt.host=mosquitto"));
        assertTrue(properties.contains("freedriver.mqtt.port=8883"));
        assertTrue(properties.contains("freedriver.mqtt.tls=true"));
        assertFalse(properties.contains("freedriver.mqtt.port=1883"));
        assertFalse(properties.contains("freedriver.mqtt.host=mqtt.freedriver.io"));
    }
}
