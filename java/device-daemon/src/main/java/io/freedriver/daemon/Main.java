package io.freedriver.daemon;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Loads config and starts the Agent Bridge ({@link OpenBotWorker}). A retired mode exits 0
     * before the secret file is read, so an installed unit with {@code Restart=on-failure} stays
     * stopped. Every other config or credential error exits 2.
     */
    static int run(String[] args) {
        Path configPath;
        try {
            configPath = configPath(args);
        } catch (IllegalArgumentException bad) {
            System.err.println(bad.getMessage());
            return 2;
        }
        DaemonConfig config;
        try {
            config = DaemonConfig.parse(Files.readString(configPath));
        } catch (RetiredModeException retired) {
            System.err.println(retired.getMessage());
            return 0;
        } catch (IllegalArgumentException bad) {
            System.err.println(bad.getMessage());
            return 2;
        } catch (Exception failed) {
            System.err.println("config was not usable");
            return 2;
        }
        SecretFile.Material material;
        try {
            material = SecretFile.read(config.secretFile());
        } catch (IllegalArgumentException bad) {
            System.err.println(bad.getMessage());
            return 2;
        } catch (Exception failed) {
            System.err.println("config was not usable");
            return 2;
        }
        connect(config, material);
        return 0;
    }

    /**
     * Starts the Agent Bridge ({@link OpenBotWorker}). A dispatcher that is down, or an OpenBot
     * that has stopped, is a retry, so the process stays up from the start.
     */
    private static void connect(DaemonConfig config, SecretFile.Material material) {
        ScriptsAgentHost openBot = ScriptsAgentHost.create(config.settings().automationDir());
        HttpsHostJobs host = HttpsHostJobs.create(config.deviceId(), material, openBot::forbidden);
        OpenBotWorker connector = OpenBotWorker.create(host, openBot, config.settings(), host::forbidden);
        Runtime.getRuntime().addShutdownHook(new Thread(connector::close, "connector-stop"));
        connector.start();
        try {
            connector.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static Path configPath(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg.startsWith("--secret") || arg.startsWith("--base-url") || arg.startsWith("--token")) {
                    throw new IllegalArgumentException("refusing secret or base URL on the command line");
                }
            }
        }
        if (args == null || args.length != 2 || !"--config".equals(args[0])
                || args[1].isBlank() || args[1].startsWith("-")) {
            throw new IllegalArgumentException("usage: device-daemon --config <file>");
        }
        return Path.of(args[1]);
    }
}
