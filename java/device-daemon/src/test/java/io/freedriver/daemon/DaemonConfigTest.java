package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonConfigTest {

    @Test
    void a_missing_mode_and_grok_are_retired_before_the_other_checks() {
        RetiredModeException missing = assertThrows(RetiredModeException.class, () -> DaemonConfig.parse("""
                {"deviceId":"pc","hostname":"luna","agents":["yuni"],"secretFile":"/no/such/secret","router":["x"]}
                """));
        assertEquals(RetiredModeException.MESSAGE, missing.getMessage());

        RetiredModeException grok = assertThrows(RetiredModeException.class, () -> DaemonConfig.parse("""
                {"deviceId":"pc","hostname":"luna","agents":["yuni"],"secretFile":"/no/such/secret","mode":"grok","router":["x"]}
                """));
        assertEquals(RetiredModeException.MESSAGE, grok.getMessage());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse("""
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["yuni"],"secretFile":"/tmp/openbot.secret","mode":"neither"}
                """));
        assertEquals("mode is grok or openbot", unknown.getMessage());
    }

    @Test
    void openbot_mode_pins_the_device_id() throws Exception {
        DaemonConfig config = DaemonConfig.parse(Files.readString(Path.of("openbot-dispatcher-worker.json.example")));

        assertEquals(OpenBotWorker.DEVICE_ID, config.deviceId());
        assertEquals(WorkerMode.OPENBOT, config.mode());
        assertEquals(List.of("PM", "Coder", "Architect", "TechOps", "Security", "Dispatcher", "Sysadmin"),
                config.settings().agents());
        assertEquals(Path.of("/home/pc/.config/OpenBot/automation"), config.settings().automationDir());
        assertNull(config.settings().grokBinary());
        assertTrue(config.settings().allow().isEmpty());
        assertTrue(config.secretFile().toString().endsWith("openbot.secret"));
    }

    @Test
    void openbot_accepts_its_six_keys() {
        DaemonConfig config = DaemonConfig.parse("""
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["Tech Ops","yuni"],"secretFile":"/tmp/openbot.secret","mode":"openbot","automationDir":"/tmp/automation"}
                """);
        assertEquals(List.of("Tech Ops", "yuni"), config.settings().agents());
        assertEquals(Path.of("/tmp/automation"), config.settings().automationDir());
    }

    @Test
    void openbot_rejects_another_device_id() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse("""
                {"deviceId":"shizuku","hostname":"luna","agents":["techops"],"secretFile":"/tmp/openbot.secret","mode":"openbot"}
                """));
        assertEquals("openbot device id is freedriver-openbot, not shizuku", error.getMessage());
    }

    @Test
    void openbot_refuses_the_settings_of_the_old_router_and_of_grok() {
        for (String key : List.of(
                "\"router\":[\"openbot-route\"]",
                "\"sessionTimeout\":\"PT1M\"",
                "\"workDir\":\"/tmp\"",
                "\"grok\":\"grok\"",
                "\"allow\":[]")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse(
                    "{\"deviceId\":\"freedriver-openbot\",\"hostname\":\"luna\",\"agents\":[\"techops\"],"
                            + "\"secretFile\":\"/tmp/openbot.secret\",\"mode\":\"openbot\"," + key + "}"));
            String name = key.substring(1, key.indexOf('"', 1));
            assertEquals(name + " is not an openbot mode setting", error.getMessage());
        }
    }

    @Test
    void automation_dir_defaults_to_the_xdg_config_home_then_the_home_folder() {
        String json = """
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["techops"],"secretFile":"/tmp/openbot.secret","mode":"openbot"}
                """;
        assertEquals(Path.of("/xdg/OpenBot/automation"),
                DaemonConfig.parse(json, Map.of("XDG_CONFIG_HOME", "/xdg"), "/home/pc").settings().automationDir());
        assertEquals(Path.of("/home/pc/.config/OpenBot/automation"),
                DaemonConfig.parse(json, Map.of(), "/home/pc").settings().automationDir());
        assertEquals(Path.of("/home/pc/.config/OpenBot/automation"),
                DaemonConfig.parse(json, Map.of("XDG_CONFIG_HOME", ""), "/home/pc").settings().automationDir());
    }

    @Test
    void the_agents_setting_is_checked_at_start() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            many.append(i == 0 ? "" : ",").append("\"agent").append(i).append("\"");
        }
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse(
                openBot(many.toString())));
        assertEquals("agents has more than 32 names", tooMany.getMessage());

        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse(
                openBot("\"" + "a".repeat(65) + "\"")));
        assertEquals("agents has a name longer than 64 characters", tooLong.getMessage());

        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse(
                openBot("\"Tech Ops\",\"tech ops\"")));
        assertEquals("agents names tech ops twice, without regard to case", twice.getMessage());

        assertEquals(64, DaemonConfig.parse(openBot("\"" + "a".repeat(64) + "\"")).settings().agents().get(0).length());
    }

    private static String openBot(String agents) {
        return "{\"deviceId\":\"freedriver-openbot\",\"hostname\":\"luna\",\"agents\":[" + agents + "],"
                + "\"secretFile\":\"/tmp/openbot.secret\",\"mode\":\"openbot\"}";
    }

    @Test
    void base_url_and_inline_secret_are_rejected_without_echoing_the_value() {
        IllegalArgumentException secret = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse("""
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["yuni"],"secretFile":"/tmp/device.secret","mode":"openbot","secret":"super-secret-value"}
                """));
        assertFalse(secret.getMessage().contains("super-secret-value"));

        IllegalArgumentException baseUrl = assertThrows(IllegalArgumentException.class, () -> DaemonConfig.parse("""
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["yuni"],"secretFile":"/tmp/device.secret","mode":"openbot","baseUrl":"http://evil.example"}
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
