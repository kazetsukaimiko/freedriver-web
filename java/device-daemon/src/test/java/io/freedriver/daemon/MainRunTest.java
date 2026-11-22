package io.freedriver.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MainRunTest {

    private static final String INSTALLED_GROK_EXAMPLE = """
            {
              "deviceId": "pc-luna",
              "hostname": "luna",
              "agents": ["yuni"],
              "secretFile": "/home/pc/.config/freedriver/device.secret",
              "allow": [],
              "sessionTimeout": "PT10M",
              "workDir": "/home/pc"
            }
            """;

    @TempDir
    Path dir;

    @Test
    void the_installed_grok_example_exits_zero() throws Exception {
        Exit exit = run(INSTALLED_GROK_EXAMPLE);
        assertEquals(0, exit.code());
        assertEquals(RetiredModeException.MESSAGE, exit.err());
    }

    @Test
    void grok_mode_exits_zero_without_a_secret_file() throws Exception {
        Exit exit = run("""
                {"deviceId":"pc","hostname":"luna","agents":["yuni"],"secretFile":"/no/such/secret","mode":"grok"}
                """);
        assertEquals(0, exit.code());
        assertEquals(RetiredModeException.MESSAGE, exit.err());
    }

    @Test
    void an_openbot_config_with_a_bad_key_exits_two() throws Exception {
        Exit exit = run("""
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["yuni"],"secretFile":"/no/such/secret","mode":"openbot","allow":[]}
                """);
        assertEquals(2, exit.code());
        assertEquals("allow is not an openbot mode setting", exit.err());
    }

    @Test
    void an_unreadable_secret_file_exits_two() throws Exception {
        Path secret = dir.resolve("openbot.secret");
        Files.writeString(secret, "not-the-secret\n");
        Files.setPosixFilePermissions(secret, EnumSet.noneOf(PosixFilePermission.class));
        Exit exit = run("""
                {"deviceId":"freedriver-openbot","hostname":"luna","agents":["yuni"],"secretFile":"%s","mode":"openbot"}
                """.formatted(secret));
        assertEquals(2, exit.code());
        assertFalse(exit.err().contains("not-the-secret"));
    }

    @Test
    void a_bad_usage_line_exits_two_without_the_value() {
        Exit exit = runArgs(new String[] {"--secret", "super-secret-value"});
        assertEquals(2, exit.code());
        assertFalse(exit.err().contains("super-secret-value"));
    }

    private Exit run(String json) throws Exception {
        Path config = dir.resolve("device-daemon.json");
        Files.writeString(config, json);
        return runArgs(new String[] {"--config", config.toString()});
    }

    private static Exit runArgs(String[] args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Exit(Main.run(args), err.toString(StandardCharsets.UTF_8).trim());
        } finally {
            System.setErr(previous);
        }
    }

    private record Exit(int code, String err) {
    }
}
