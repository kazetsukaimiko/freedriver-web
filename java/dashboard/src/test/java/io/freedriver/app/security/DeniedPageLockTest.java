package io.freedriver.app.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeniedPageLockTest {

    @Test
    void denied_uses_red_flake_not_404() throws IOException {
        Path deniedAsset = Path.of("src/main/webui/public/assets/freedriver/pages/freedriver-denied.png");
        assertTrue(Files.isRegularFile(deniedAsset), "denied flake");
        String dashboard = Files.readString(Path.of("src/main/webui/src/Dashboard.tsx"));
        assertTrue(dashboard.contains("freedriver-denied.png"));
        assertFalse(dashboard.contains("freedriver-404.png"));
        String app = Files.readString(Path.of("src/main/webui/src/App.tsx"));
        assertTrue(app.contains("freedriver-404.png"), "404 page still uses the 404 flake");
        assertFalse(app.contains("java-script-auto-redirect"));
    }
}
