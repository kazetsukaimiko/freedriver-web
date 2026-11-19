package io.freedriver.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class Main {

    private static final Duration HEARTBEAT = Duration.ofSeconds(30);
    private static final Duration POLL_BACKOFF = Duration.ofSeconds(2);

    private Main() {
    }

    public static void main(String[] args) {
        Path configPath;
        try {
            configPath = configPath(args);
        } catch (IllegalArgumentException bad) {
            System.err.println(bad.getMessage());
            System.exit(2);
            return;
        }
        DaemonConfig config;
        SecretFile.Material material;
        try {
            config = DaemonConfig.parse(Files.readString(configPath));
            material = SecretFile.read(config.secretFile());
        } catch (IllegalArgumentException bad) {
            System.err.println(bad.getMessage());
            System.exit(2);
            return;
        } catch (Exception failed) {
            System.err.println("config was not usable");
            System.exit(2);
            return;
        }
        if (config.mode() == WorkerMode.OPENBOT) {
            connect(config, material);
            return;
        }
        HttpsDeviceClient http = HttpsDeviceClient.create(config.deviceId(), material);
        DeviceWorker worker = new DeviceDaemon(
                http,
                new ProcessGrokLauncher(http::forbidden),
                config.settings());
        try {
            worker.registerOnce();
        } catch (RuntimeException failed) {
            System.err.println("register failed");
            System.exit(1);
            return;
        }
        Thread heartbeat = Thread.ofPlatform().name("device-heartbeat").unstarted(() -> beat(worker));
        Thread poller = Thread.ofPlatform().name("device-poll").unstarted(() -> poll(worker));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                worker.close();
            } catch (Exception ignored) {
                // shutdown
            }
            heartbeat.interrupt();
            poller.interrupt();
        }, "device-stop"));
        heartbeat.start();
        poller.start();
        try {
            poller.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * OpenBot mode: the dispatch connector. A dispatcher that is down and an OpenBot that is not
     * running are retries, so the process stays up from the start.
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

    private static void beat(DeviceWorker worker) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                worker.heartbeatOnce();
                Thread.sleep(HEARTBEAT);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failed) {
                System.err.println("heartbeat failed");
                try {
                    Thread.sleep(POLL_BACKOFF);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void poll(DeviceWorker worker) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                worker.pollOnce();
            } catch (RuntimeException failed) {
                System.err.println("poll failed");
                try {
                    Thread.sleep(POLL_BACKOFF);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
