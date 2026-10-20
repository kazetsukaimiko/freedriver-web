package io.freedriver.daemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Argument array for one headless session. The prompt is not a parameter.
 * An empty allowlist grants no tools. {@code --always-approve} does not widen that list.
 */
final class GrokCommand {

    static final String DUMMY_DENIED = "read_file";

    private static final List<String> CLOSED_DENY = List.of(
            "read_file",
            "grep",
            "list_dir",
            "search_replace",
            "write",
            "run_terminal_cmd",
            "run_terminal_command",
            "web_search",
            "web_fetch",
            "todo_write",
            "task",
            "spawn_subagent",
            "get_task_output",
            "wait_tasks",
            "kill_task",
            "hashline_read",
            "hashline_grep",
            "hashline_edit",
            "image_gen",
            "image_edit",
            "CallMcpTool",
            "GetMcpTools",
            "FetchMcpResource",
            "ListMcpResources",
            "Agent",
            "Shell",
            "Await",
            "StrReplace",
            "EditNotebook",
            "Delete",
            "GenerateImage",
            "GenerateVideo");

    private static final List<String> ALWAYS_OFF_UNLESS_GRANTED = List.of(
            "CallMcpTool",
            "GetMcpTools",
            "FetchMcpResource",
            "ListMcpResources",
            "Agent");

    private GrokCommand() {
    }

    static List<String> sanitize(List<String> allow) {
        if (allow == null || allow.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String tool : allow) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String trimmed = tool.trim();
            if (trimmed.startsWith("-") || !trimmed.matches("[A-Za-z0-9_.:-]{1,64}")) {
                throw new IllegalArgumentException("tool name is not safe");
            }
            if (!out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }

    static List<String> arguments(
            String binary, Path promptFile, UUID sessionId, String agentName, List<String> allow) {
        if (binary == null || binary.isBlank() || binary.startsWith("-")
                || binary.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("grok binary is not safe");
        }
        if (promptFile == null || !promptFile.isAbsolute() || promptFile.toString().startsWith("-")) {
            throw new IllegalArgumentException("prompt file is not safe");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("session id is required");
        }
        if (agentName == null || agentName.startsWith("-") || !agentName.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("agent name is not safe");
        }
        List<String> granted = sanitize(allow);
        List<String> argv = new ArrayList<>();
        argv.add(binary);
        argv.add("--prompt-file");
        argv.add(promptFile.toString());
        argv.add("--session-id");
        argv.add(sessionId.toString());
        argv.add("--agent");
        argv.add(agentName);
        argv.add("--output-format");
        argv.add("json");
        argv.add("--verbatim");
        argv.add("--no-subagents");
        if (granted.isEmpty()) {
            argv.add("--permission-mode");
            argv.add("dontAsk");
            argv.add("--tools");
            argv.add(DUMMY_DENIED);
            argv.add("--disallowed-tools");
            argv.add(String.join(",", CLOSED_DENY));
        } else {
            argv.add("--always-approve");
            argv.add("--tools");
            argv.add(String.join(",", granted));
            List<String> deny = new ArrayList<>();
            for (String tool : ALWAYS_OFF_UNLESS_GRANTED) {
                if (!granted.contains(tool)) {
                    deny.add(tool);
                }
            }
            if (!deny.isEmpty()) {
                argv.add("--disallowed-tools");
                argv.add(String.join(",", deny));
            }
        }
        return List.copyOf(argv);
    }

    static List<String> grantedTools(List<String> argv) {
        Set<String> denied = new HashSet<>(split(flag(argv, "--disallowed-tools")));
        List<String> granted = new ArrayList<>();
        for (String tool : split(flag(argv, "--tools"))) {
            if (!denied.contains(tool)) {
                granted.add(tool);
            }
        }
        return List.copyOf(granted);
    }

    static String flag(List<String> argv, String name) {
        for (int i = 0; i < argv.size() - 1; i++) {
            if (name.equals(argv.get(i))) {
                return argv.get(i + 1);
            }
        }
        return null;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(",", -1));
    }
}
