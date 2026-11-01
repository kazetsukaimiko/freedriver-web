package io.freedriver.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ProcessAgentRouter implements AgentRouter {

    private final List<String> command;
    private final Path workDir;
    private final Supplier<List<String>> forbidden;

    ProcessAgentRouter(List<String> command, Path workDir, Supplier<List<String>> forbidden) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("router is required");
        }
        this.command = List.copyOf(command);
        this.workDir = workDir;
        this.forbidden = forbidden;
    }

    @Override
    public RouteResult route(String agentName, String prompt, Duration timeout) {
        Path promptFile = null;
        try {
            promptFile = writePrompt(prompt);
            List<String> argv = new ArrayList<>(command);
            argv.add(agentName);
            argv.add("--prompt-file");
            argv.add(promptFile.toString());
            List<String> secrets = forbidden == null ? List.of() : forbidden.get();
            Map<String, String> env = ProcessLaunch.childEnvironment(System.getenv(), secrets);
            ProcessLaunch.rejectSecrets(argv, env, secrets);
            Process process = ProcessLaunch.builder(argv, env, workDir).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RouteResult(false, "local timeout");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String note = Notes.forUpdate(output, secrets.toArray(String[]::new));
            if (process.exitValue() == 0) {
                return new RouteResult(true, blankToNull(note));
            }
            return new RouteResult(false, blankToNull(note) == null ? "agent exited " + process.exitValue() : note);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new RouteResult(false, "interrupted");
        } catch (IOException | IllegalArgumentException failed) {
            return new RouteResult(false, "agent routing failed");
        } finally {
            delete(promptFile);
        }
    }

    private static Path writePrompt(String prompt) throws IOException {
        if (prompt == null) {
            throw new IOException("missing prompt");
        }
        Path file = Files.createTempFile("freedriver-openbot-prompt-", ".txt");
        Files.setPosixFilePermissions(
                file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(file, prompt, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        return file.toAbsolutePath();
    }

    private static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the assignment is already over
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
