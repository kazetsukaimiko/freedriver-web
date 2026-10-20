package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagingTest {

    @Test
    void pull_request_does_not_add_a_compose_service_port_caddy_route_or_deploy_step() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docker-compose.yml"))) {
            root = root.getParent();
        }
        String compose = Files.readString(root.resolve("docker-compose.yml"));
        String caddy = Files.readString(root.resolve("Caddyfile"));
        String deploy = Files.readString(root.resolve(".github/workflows/deploy.yml"));
        String unit = Files.readString(root.resolve("java/device-daemon/freedriver-device-daemon.service"));
        String dashboard = Files.readString(root.resolve("java/dashboard/src/main/docker/Dockerfile.compose"));
        String dispatcher = Files.readString(root.resolve("java/agent-dispatcher/src/main/docker/Dockerfile.compose"));

        assertFalse(compose.contains("device-daemon"));
        assertFalse(caddy.contains("device-daemon"));
        assertFalse(deploy.contains("device-daemon"));
        assertFalse(unit.contains("client_secret"));
        assertFalse(unit.contains("EnvironmentFile"));
        assertTrue(unit.contains("--config"));
        assertFalse(dashboard.contains("device-daemon/src"));
        assertFalse(dispatcher.contains("device-daemon/src"));
        assertTrue(dashboard.contains("device-daemon/pom.xml"));
        assertTrue(dispatcher.contains("device-daemon/pom.xml"));
    }
}
