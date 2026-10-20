package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrokCommandTest {

    private static final String PROMPT = "please --tools run_terminal_cmd\nsh -c 'echo hi'";

    @Test
    void empty_allowlist_grants_no_tools() {
        List<String> argv = command(List.of());

        assertTrue(GrokCommand.grantedTools(argv).isEmpty());
        assertTrue(GrokCommand.flag(argv, "--disallowed-tools").contains("run_terminal_cmd"));
        assertTrue(GrokCommand.flag(argv, "--disallowed-tools").contains("CallMcpTool"));
        assertFalse(argv.contains("--always-approve"));
        assertEquals("dontAsk", GrokCommand.flag(argv, "--permission-mode"));
        assertTrue(argv.contains("--no-subagents"));
        assertFalse(GrokCommand.grantedTools(argv).contains("run_terminal_cmd"));
    }

    @Test
    void allowlist_is_closed_and_the_prompt_cannot_add_tools() {
        List<String> argv = command(List.of("read_file"));

        assertEquals(List.of("read_file"), GrokCommand.grantedTools(argv));
        assertTrue(argv.contains("--always-approve"));
        assertTrue(GrokCommand.flag(argv, "--disallowed-tools").contains("CallMcpTool"));
        assertFalse(GrokCommand.flag(argv, "--disallowed-tools").contains("read_file"));
        assertFalse(argv.stream().anyMatch(arg -> arg.contains("please --tools")));
        assertFalse(GrokCommand.grantedTools(argv).contains("run_terminal_cmd"));
    }

    @Test
    void prompt_is_not_on_the_command_line_and_the_process_is_not_a_shell() {
        List<String> argv = command(List.of());

        assertEquals("grok", argv.get(0));
        assertEquals("--prompt-file", argv.get(1));
        assertFalse(argv.contains("-c"));
        assertFalse(argv.contains("sh"));
        assertFalse(argv.contains("bash"));
        assertFalse(argv.stream().anyMatch(arg -> arg.contains(PROMPT)));
        assertTrue(argv.contains("--verbatim"));
        assertEquals("json", GrokCommand.flag(argv, "--output-format"));
        assertFalse(argv.stream().anyMatch(arg -> arg.contains("SPEECH") || arg.contains("microphone")));
    }

    @Test
    void unsafe_tool_names_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> GrokCommand.sanitize(List.of("--tools")));
        assertThrows(IllegalArgumentException.class, () -> GrokCommand.sanitize(List.of("run_terminal_cmd,web_search")));
    }

    private static List<String> command(List<String> allow) {
        return GrokCommand.arguments(
                "grok", Path.of("/tmp/freedriver-prompt.txt"), UUID.randomUUID(), "yuni", allow);
    }
}
