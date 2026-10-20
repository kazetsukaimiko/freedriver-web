package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NoMqttInWebuiTest {

    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".quinoa", "dist", "build");
    private static final Set<String> TEXT_EXT = Set.of(
            ".ts", ".tsx", ".js", ".jsx", ".json", ".css", ".html", ".md", ".svg");

    @Test
    void browser_must_not_speak_mqtt() throws IOException {
        Path webui = Path.of("src/main/webui");
        assertTrue(Files.isDirectory(webui), "webui directory");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(webui)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                if (path.toString().contains("/node_modules/") || path.toString().contains("/.quinoa/")) {
                    return;
                }
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean text = TEXT_EXT.stream().anyMatch(name::endsWith);
                if (!text) {
                    return;
                }
                try {
                    String body = Files.readString(path).toLowerCase(Locale.ROOT);
                    if (body.contains("mqtt") || body.contains("mosquitto")) {
                        hits.add(path.toString());
                    }
                } catch (IOException ignored) {
                    // skip unreadable files
                }
            });
        }
        assertTrue(hits.isEmpty(), "webui must not mention MQTT: " + hits);
    }
}
