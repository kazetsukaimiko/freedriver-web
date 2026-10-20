package io.freedriver.app.appliances;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplianceServiceSurfaceTest {

    @Test
    void service_has_no_jaxrs_identity_or_limiter() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/freedriver/app/appliances/ApplianceService.java"));
        assertFalse(source.contains("jakarta.ws.rs"), source);
        assertFalse(source.contains("SecurityIdentity"), source);
        assertFalse(source.contains("CommandRateLimiter"), source);
        assertFalse(source.contains("assertCanAccess"), source);
        assertFalse(source.contains("CommandTimeoutException"), source);
    }

    @Test
    void control_has_no_jaxrs_or_identity() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/freedriver/app/appliances/ApplianceControl.java"));
        assertFalse(source.contains("jakarta.ws.rs"), source);
        assertFalse(source.contains("SecurityIdentity"), source);
        assertFalse(source.contains("CommandRateLimiter"), source);
    }
}
