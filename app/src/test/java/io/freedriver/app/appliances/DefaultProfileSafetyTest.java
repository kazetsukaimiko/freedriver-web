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
    void default_profile_keeps_prod_flags_off() throws IOException {
        Path props = Path.of("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(props), "application.properties");
        List<String> defaults = Files.readAllLines(props).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("%"))
                .toList();
        assertTrue(defaults.contains("freedriver.appliances.enabled=false"));
        assertTrue(defaults.contains("freedriver.appliances.live-commands=false"));
        assertTrue(defaults.contains("freedriver.appliances.backend=none"));
        assertTrue(defaults.contains("quarkus.oidc.enabled=false"));
        assertFalse(defaults.contains("freedriver.appliances.backend=mock"));
        assertFalse(defaults.contains("freedriver.appliances.backend=fake"));
        assertFalse(defaults.contains("freedriver.appliances.backend=mock-autonomy"));
        assertFalse(defaults.contains("freedriver.appliances.enabled=true"));
        assertFalse(defaults.contains("freedriver.appliances.live-commands=true"));
        assertFalse(defaults.contains("quarkus.oidc.enabled=true"));
        assertFalse(defaults.stream().anyMatch(line -> line.contains("auth-required")));
        assertFalse(defaults.stream().anyMatch(line -> line.contains("enabled-in-dev-mode")));
        assertFalse(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/FakeApplianceBackend.java")));
        assertFalse(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/DevAuthMechanism.java")));
        assertTrue(Files.exists(Path.of("src/main/java/io/freedriver/app/appliances/MockAutonomy.java")));
    }
}
