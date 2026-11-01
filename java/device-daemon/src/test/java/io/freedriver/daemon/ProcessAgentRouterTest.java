package io.freedriver.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessAgentRouterTest {

    @TempDir
    Path dir;

    @Test
    void routes_the_named_agent_through_a_prompt_file() throws Exception {
        Path script = script("""
                import sys
                from pathlib import Path
                agent = sys.argv[1]
                assert sys.argv[2] == "--prompt-file"
                prompt = Path(sys.argv[3]).read_text()
                print(agent + ":" + prompt.strip(), end="")
                """);
        ProcessAgentRouter router = new ProcessAgentRouter(
                List.of("/usr/bin/python3", script.toString()), dir, () -> List.of("device-secret-value"));

        AgentRouter.RouteResult result = router.route("techops", "check appliance states", Duration.ofSeconds(5));

        assertTrue(result.succeeded());
        assertEquals("techops:check appliance states", result.note());
        assertFalse(result.note().contains("device-secret-value"));
    }

    @Test
    void local_timeout_kills_the_child() throws Exception {
        Path script = script("""
                import time
                time.sleep(30)
                """);
        ProcessAgentRouter router = new ProcessAgentRouter(
                List.of("/usr/bin/python3", script.toString()), dir, List::of);

        AgentRouter.RouteResult result = router.route("techops", "hello", Duration.ofMillis(80));

        assertFalse(result.succeeded());
        assertEquals("local timeout", result.note());
    }

    @Test
    void non_zero_exit_fails() throws Exception {
        Path script = script("raise SystemExit(3)\n");
        ProcessAgentRouter router = new ProcessAgentRouter(
                List.of("/usr/bin/python3", script.toString()), dir, List::of);

        AgentRouter.RouteResult result = router.route("yuni", "hello", Duration.ofSeconds(5));

        assertFalse(result.succeeded());
        assertEquals("agent exited 3", result.note());
    }

    @Test
    void shell_argv_is_routing_failure() {
        ProcessAgentRouter router = new ProcessAgentRouter(List.of("/usr/bin/python3", "-c", "print(1)"), dir, List::of);

        AgentRouter.RouteResult result = router.route("techops", "hello", Duration.ofSeconds(1));

        assertFalse(result.succeeded());
        assertEquals("agent routing failed", result.note());
    }

    private Path script(String body) throws Exception {
        Path file = dir.resolve("route.py");
        Files.writeString(file, body);
        return file;
    }
}
