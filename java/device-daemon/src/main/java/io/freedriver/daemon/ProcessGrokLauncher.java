package io.freedriver.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ProcessGrokLauncher implements GrokLauncher {

    private final Supplier<List<String>> forbidden;

    ProcessGrokLauncher(Supplier<List<String>> forbidden) {
        this.forbidden = forbidden;
    }

    @Override
    public RunningGrok start(GrokRequest request) {
        try {
            return prepare(request, System.getenv()).start();
        } catch (IOException | IllegalArgumentException | GrokLaunchException failed) {
            if (failed instanceof GrokLaunchException launch) {
                throw launch;
            }
            throw new GrokLaunchException("grok did not start");
        }
    }

    Prepared prepare(GrokRequest request, Map<String, String> parentEnv) throws IOException {
        Path promptFile = writePrompt(request.prompt());
        try {
            List<String> command = GrokCommand.arguments(
                    request.binary(),
                    promptFile,
                    request.sessionId(),
                    request.agentName(),
                    request.allow());
            Map<String, String> env = ProcessLaunch.childEnvironment(parentEnv, forbidden.get());
            ProcessLaunch.rejectSecrets(command, env, forbidden.get());
            ProcessBuilder builder = ProcessLaunch.builder(command, env, request.workDir());
            return new Prepared(List.copyOf(command), Map.copyOf(env), promptFile, builder);
        } catch (RuntimeException failed) {
            delete(promptFile);
            throw failed;
        }
    }

    private static Path writePrompt(String prompt) throws IOException {
        if (prompt == null) {
            throw new IOException("missing prompt");
        }
        Path file = Files.createTempFile("freedriver-prompt-", ".txt");
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
            // the session is already over
        }
    }

    record Prepared(List<String> command, Map<String, String> environment, Path promptFile, ProcessBuilder builder) {

        RunningGrok start() throws IOException {
            try {
                return new Handle(builder.start(), promptFile);
            } catch (IOException failed) {
                delete(promptFile);
                throw failed;
            }
        }
    }

    private static final class Handle implements RunningGrok {

        private final Process process;
        private final Path promptFile;
        private final StringBuilder output = new StringBuilder();
        private final Thread drain;

        private Handle(Process process, Path promptFile) {
            this.process = process;
            this.promptFile = promptFile;
            this.drain = Thread.ofPlatform().name("grok-output").start(this::drain);
        }

        private void drain() {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    synchronized (output) {
                        if (output.length() >= 65_536) {
                            continue;
                        }
                        int room = 65_536 - output.length();
                        output.append(new String(buffer, 0, Math.min(read, room), StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // the process is going away
            }
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public void kill() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public int exitCode() {
            if (process.isAlive()) {
                return -1;
            }
            return process.exitValue();
        }

        @Override
        public String output() {
            synchronized (output) {
                return output.toString();
            }
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                kill();
            }
            try {
                drain.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            delete(promptFile);
        }
    }
}
