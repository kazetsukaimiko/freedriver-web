package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The api password stays in a host file, and git does not carry it. Dev and test name their
 * house ids in a comma-separated list.
 */
final class MqttHouseIds {

    private MqttHouseIds() {
    }

    static String password(String file) {
        if (file == null || file.isBlank()) {
            return "";
        }
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path).strip();
        } catch (IOException e) {
            MqttLog.configUnreadable("api-pass");
            return "";
        }
    }

    static List<UUID> parseList(String rawList) {
        String raw = rawList == null ? "" : rawList.strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        Set<UUID> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.strip();
            if (id.isEmpty()) {
                continue;
            }
            out.add(UUID.fromString(id));
        }
        return List.copyOf(out);
    }
}
