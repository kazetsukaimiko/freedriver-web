package io.freedriver.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessGrokLauncherTest {

    @TempDir
    Path dir;

    @Test
    void prompt_is_a_file_and_the_child_environment_has_no_secret() throws Exception {
        String prompt = "say hello; sh -c \"nope\"";
        ProcessGrokLauncher launcher = new ProcessGrokLauncher(() -> List.of("device-secret-value"));
        Map<String, String> parent = Map.of(
                "PATH", "/usr/bin",
                "HOME", "/home/pc",
                "USER", "pc",
                "GH_TOKEN", "ghp_should_not_pass",
                "SECRET", "device-secret-value");
        GrokRequest request = new GrokRequest("/bin/true", UUID.randomUUID(), "yuni", List.of(), prompt, dir);

        ProcessGrokLauncher.Prepared prepared = launcher.prepare(request, parent);

        assertEquals(prompt, Files.readString(prepared.promptFile()));
        assertEquals(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(prepared.promptFile()));
        assertEquals("/bin/true", prepared.command().get(0));
        assertFalse(prepared.command().stream().anyMatch(arg -> arg.contains(prompt)));
        assertFalse(prepared.command().contains("-c"));
        assertFalse(prepared.command().contains("sh"));
        assertEquals(Set.of("PATH", "HOME", "LANG", "USER"), prepared.environment().keySet());
        assertFalse(prepared.environment().containsKey("GH_TOKEN"));
        assertFalse(prepared.environment().containsKey("SECRET"));
        assertEquals(prepared.command(), prepared.builder().command());
        assertEquals("/usr/bin", prepared.builder().environment().get("PATH"));
        assertFalse(prepared.builder().environment().containsKey("GH_TOKEN"));
        assertFalse(prepared.builder().environment().containsKey("SECRET"));
        assertFalse(prepared.builder().environment().containsKey("AUTHORIZATION"));
        RunningGrok running = prepared.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (running.alive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(running.alive());
        assertEquals(0, running.exitCode());
        running.close();
        assertFalse(Files.exists(prepared.promptFile()));
    }
}
