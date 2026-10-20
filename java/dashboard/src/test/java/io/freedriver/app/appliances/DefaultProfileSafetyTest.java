package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void prod_profile_turns_on_sign_in_and_keeps_appliance_control_off() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/application.properties")).stream()
                .map(String::strip)
                .toList();
        assertTrue(lines.contains("quarkus.oidc.enabled=false"));
        assertTrue(lines.contains("%dev.quarkus.oidc.enabled=false"));
        assertTrue(lines.contains("%test.quarkus.oidc.enabled=false"));
        assertTrue(lines.contains("%prod.quarkus.oidc.enabled=true"));
        assertTrue(lines.contains("freedriver.appliances.enabled=false"));
        assertTrue(lines.contains("freedriver.appliances.live-commands=false"));
        for (String line : lines) {
            assertFalse(line.startsWith("%prod.freedriver.appliances.enabled"), line);
            assertFalse(line.startsWith("%prod.freedriver.appliances.live-commands"), line);
            assertFalse(line.startsWith("freedriver.appliances.enabled=true"), line);
            assertFalse(line.startsWith("freedriver.appliances.live-commands=true"), line);
        }
    }
}
