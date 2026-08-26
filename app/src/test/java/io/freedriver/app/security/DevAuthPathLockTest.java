package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevAuthPathLockTest {

    @Test
    void augmentor_is_dev_profile_only() throws IOException {
        Path source = Path.of("src/main/java/io/freedriver/app/security/DevOpenAuthAugmentor.java");
        assertTrue(Files.isRegularFile(source), "DevOpenAuthAugmentor must stay in security");
        String body = Files.readString(source);
        assertTrue(body.contains("@IfBuildProfile(\"dev\")"), "augmentor is %dev only");
        assertFalse(body.contains("@IfBuildProfile(\"test\")"), "augmentor must never be on the test profile");
        assertFalse(body.contains("@IfBuildProfile(\"prod\")"), "augmentor must never be on the prod profile");
    }

    @Test
    void appliances_has_no_auth_types() throws IOException {
        Path appliances = Path.of("src/main/java/io/freedriver/app/appliances");
        assertTrue(Files.isDirectory(appliances), "appliances package");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(appliances)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String body = Files.readString(path);
                    if (body.contains("HttpAuthenticationMechanism")
                            || body.contains("SecurityIdentityAugmentor")
                            || body.contains("AuthorizationController")) {
                        hits.add(path.getFileName().toString());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(path.toString(), e);
                }
            });
        }
        assertTrue(hits.isEmpty(), "no auth types under appliances: " + hits);
    }

    @Test
    void no_second_dev_escape_in_properties() throws IOException {
        Path props = Path.of("src/main/resources/application.properties");
        List<String> assignments = Files.readAllLines(props).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
        assertTrue(assignments.stream().noneMatch(line -> line.contains("auth-required")),
                "auth-required knob is gone");
        assertTrue(assignments.stream().noneMatch(line -> line.contains("enabled-in-dev-mode")),
                "do not disable auth in %dev");
    }
}
