package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessLaunchTest {

    @Test
    void child_environment_drops_credentials() {
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin",
                "HOME", "/home/pc",
                "USER", "pc",
                "GH_TOKEN", "ghp_should_not_pass",
                "SECRET", "device-secret-value",
                "AUTHORIZATION", "Bearer device-secret-value");

        Map<String, String> child = ProcessLaunch.childEnvironment(parent, List.of("device-secret-value"));

        assertEquals(Set.of("PATH", "HOME", "LANG", "USER"), child.keySet());
        assertEquals("/usr/bin", child.get("PATH"));
        assertEquals("C.UTF-8", child.get("LANG"));
        assertFalse(child.containsKey("GH_TOKEN"));
        assertFalse(child.containsKey("SECRET"));
        assertFalse(child.values().stream().anyMatch(value -> value.contains("device-secret-value")));
    }

    @Test
    void credential_in_a_copied_variable_is_refused() {
        Map<String, String> parent = Map.of("HOME", "/home/device-secret-value", "PATH", "/usr/bin");

        assertThrows(
                IllegalArgumentException.class,
                () -> ProcessLaunch.childEnvironment(parent, List.of("device-secret-value")));
    }

    @Test
    void shell_is_refused() {
        Path dir = Path.of("/tmp");
        assertThrows(IllegalArgumentException.class, () -> ProcessLaunch.builder(List.of("sh", "-c", "echo"), Map.of(), dir));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProcessLaunch.builder(List.of("/bin/bash", "-c", "echo hi"), Map.of(), dir));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProcessLaunch.builder(List.of("/usr/bin/grok", "-c", "echo"), Map.of(), dir));
    }
}
