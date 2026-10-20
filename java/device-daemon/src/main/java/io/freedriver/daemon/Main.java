package io.freedriver.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
        HttpsDeviceClient http = HttpsDeviceClient.create(config.deviceId(), material);
        DeviceDaemon daemon = new DeviceDaemon(
                http,
                new ProcessGrokLauncher(() -> forbidden(material, http)),
                config.settings());
        try {
            daemon.registerOnce();
        } catch (RuntimeException failed) {
            System.err.println("register failed");
            System.exit(1);
            return;
        }
        Thread heartbeat = Thread.ofPlatform().name("device-heartbeat").unstarted(() -> beat(daemon));
        Thread poller = Thread.ofPlatform().name("device-poll").unstarted(() -> poll(daemon));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            daemon.close();
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

    private static List<String> forbidden(SecretFile.Material material, HttpsDeviceClient http) {
        List<String> values = new ArrayList<>();
        values.add(material.secret());
        values.add(material.raw());
        String token = http.currentToken();
        if (token != null) {
            values.add(token);
        }
        return values;
    }

    private static void beat(DeviceDaemon daemon) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                daemon.heartbeatOnce();
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

    private static void poll(DeviceDaemon daemon) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                daemon.pollOnce();
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
