package io.freedriver.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoPerServiceConfigTest {

    @Test
    void each_service_has_its_own_database_and_env_file() throws IOException {
        String compose = Files.readString(Path.of("../../docker-compose.yml"));
        String dashboard = Files.readString(Path.of("src/main/resources/application.properties"));
        String sms = Files.readString(Path.of("../../sms/src/main/resources/application.properties"));
        String dispatcher = Files.readString(
                Path.of("../../java/agent-dispatcher/src/main/resources/application.properties"));

        assertTrue(unprofiledEquals(dashboard, "quarkus.mongodb.database", "freedriver_dashboard"));
        assertTrue(unprofiledEquals(sms, "quarkus.mongodb.database", "freedriver_sms"));
        assertTrue(unprofiledEquals(dispatcher, "quarkus.mongodb.database", "freedriver_dispatcher"));
        assertTrue(dashboard.contains("%test.quarkus.mongodb.database=freedriver-test-${quarkus.uuid}"));
        assertTrue(sms.contains("%test.quarkus.mongodb.database=freedriver-test-${quarkus.uuid}"));

        assertTrue(compose.contains("path: /opt/freedriver-secrets/mongo-dashboard.env"));
        assertTrue(compose.contains("path: /opt/freedriver-secrets/mongo-sms.env"));
        assertTrue(compose.contains("path: /opt/freedriver-secrets/mongo-dispatcher.env"));
        assertTrue(compose.contains("command: [\"mongod\", \"--bind_ip_all\", \"--auth\"]"));
        assertTrue(compose.contains("FREEDRIVER_APPLIANCES_LIVE_COMMANDS: \"true\""));
        assertFalse(compose.contains("QUARKUS_MONGODB_CONNECTION_STRING: ${"));
        assertFalse(compose.contains("QUARKUS_MONGODB_DATABASE:"));
        assertFalse(compose.contains("mongodb://mongodb:27017/freedriver"));
    }

    @Test
    void provision_script_check_passes() throws Exception {
        Path root = Path.of("../..").toAbsolutePath().normalize();
        Process process = new ProcessBuilder("bash", "scripts/provision-mongo-users.sh", "--check")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("provision-mongo-users --check ok"), output);
    }

    private static boolean unprofiledEquals(String properties, String key, String value) {
        String needle = key + "=" + value;
        for (String line : properties.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("%")) {
                continue;
            }
            if (trimmed.equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
