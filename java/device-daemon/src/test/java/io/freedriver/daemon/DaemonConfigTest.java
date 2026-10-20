package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonConfigTest {

    @Test
    void missing_allowlist_is_default_deny_and_the_timeout_is_not_the_queue_deadline() throws Exception {
        DaemonConfig config = DaemonConfig.parse(Files.readString(Path.of("device-daemon.json.example")));

        assertTrue(config.settings().allow().isEmpty());
        assertEquals(Duration.ofMinutes(10), config.settings().sessionTimeout());
        assertEquals(DaemonConfig.DEFAULT_SESSION_TIMEOUT, config.settings().sessionTimeout());
        assertFalse(config.settings().sessionTimeout().equals(Duration.ofSeconds(75)));
        assertEquals("pc-luna", config.deviceId());
        assertEquals("grok", config.settings().grokBinary());
    }

    @Test
    void base_url_and_inline_secret_are_rejected_without_echoing_the_value() {
        IllegalArgumentException secret = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse("""
                {"deviceId":"pc","hostname":"luna","agents":["yuni"],"secretFile":"/tmp/device.secret","secret":"super-secret-value"}
                """));
        assertFalse(secret.getMessage().contains("super-secret-value"));

        IllegalArgumentException baseUrl = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse("""
                {"deviceId":"pc","hostname":"luna","agents":["yuni"],"secretFile":"/tmp/device.secret","baseUrl":"http://evil.example"}
                """));
        assertFalse(baseUrl.getMessage().contains("evil.example"));
        assertTrue(baseUrl.getMessage().contains("baseUrl"));
    }

    @Test
    void command_line_cannot_carry_a_secret_or_a_base_url() {
        IllegalArgumentException secret = assertThrows(
                IllegalArgumentException.class,
                () -> Main.configPath(new String[] {"--secret", "super-secret-value"}));
        assertFalse(secret.getMessage().contains("super-secret-value"));
        IllegalArgumentException baseUrl = assertThrows(
                IllegalArgumentException.class,
                () -> Main.configPath(new String[] {"--base-url", "http://evil.example"}));
        assertFalse(baseUrl.getMessage().contains("evil.example"));
        assertEquals(Path.of("/tmp/device-daemon.json"), Main.configPath(new String[] {"--config", "/tmp/device-daemon.json"}));
    }
}
