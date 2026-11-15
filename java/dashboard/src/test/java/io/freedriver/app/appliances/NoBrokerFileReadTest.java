package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The houses come from Keycloak (#483). The dashboard neither reads nor mounts the broker ACL. */
class NoBrokerFileReadTest {

    private static final List<String> ACL_READS = List.of(
            "mosquitto/acl", "instance-ids-file", "parseAcl", "instanceIdsFile");

    @Test
    void dashboard_sources_and_config_name_no_broker_acl() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".properties"))
                        .forEach(path -> {
                            String text = read(path);
                            for (String needle : ACL_READS) {
                                if (text.contains(needle)) {
                                    hits.add(path + ": " + needle);
                                }
                            }
                        });
            }
        }
        assertTrue(hits.isEmpty(), hits::toString);
    }

    @Test
    void the_dashboard_service_mounts_no_broker_acl() throws IOException {
        String compose = Files.readString(Path.of("../../docker-compose.yml"));
        int start = compose.indexOf("\n  dashboard:\n");
        assertTrue(start >= 0, "dashboard service");
        int end = compose.indexOf("\n  agent-dispatcher:\n", start);
        String dashboard = compose.substring(start, end < 0 ? compose.length() : end);
        assertTrue(dashboard.contains("mosquitto/api.pass"), dashboard);
        assertFalse(dashboard.contains("mosquitto/acl"), dashboard);
        assertFalse(dashboard.contains("- 1883"), dashboard);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(path.toString(), e);
        }
    }
}
