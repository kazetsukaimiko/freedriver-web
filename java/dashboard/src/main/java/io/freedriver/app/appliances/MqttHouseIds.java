package io.freedriver.app.appliances;

import io.freedriver.mqtt.MqttLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * House instance ids and the api password stay in host files. Git does not carry them.
 * A comma-separated list wins when it is set. Otherwise the id is read from the
 * broker ACL ({@code freedriver/v1/<uuid>/appliances} topic lines only).
 */
final class MqttHouseIds {

    private static final Pattern APPLIANCES_TOPIC = Pattern.compile(
            "freedriver/v1/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/appliances(?!\\S)");

    private MqttHouseIds() {
    }

    static List<UUID> instances(String rawList, String file) {
        String raw = rawList == null ? "" : rawList.strip();
        if (!raw.isEmpty()) {
            return parseList(raw);
        }
        return fromFile(file);
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

    static List<UUID> fromFile(String file) {
        if (file == null || file.isBlank()) {
            return List.of();
        }
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return parseDocument(Files.readString(path));
        } catch (IOException e) {
            MqttLog.configUnreadable("instance-ids");
            return List.of();
        }
    }

    static List<UUID> parseDocument(String text) {
        String stripped = text == null ? "" : text.strip();
        if (stripped.isEmpty()) {
            return List.of();
        }
        if (stripped.contains("freedriver/v1/") || stripped.contains("topic ") || stripped.contains("__INSTANCE_ID__")) {
            return parseAcl(stripped);
        }
        return parseList(stripped.replace('\n', ','));
    }

    static List<UUID> parseAcl(String text) {
        Matcher matcher = APPLIANCES_TOPIC.matcher(text == null ? "" : text);
        Set<UUID> ids = new LinkedHashSet<>();
        while (matcher.find()) {
            ids.add(UUID.fromString(matcher.group(1)));
        }
        return List.copyOf(ids);
    }

    private static List<UUID> parseList(String raw) {
        List<UUID> out = new ArrayList<>();
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
