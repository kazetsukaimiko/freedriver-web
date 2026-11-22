package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared transport: fixed https origin, no redirects, and form encoding of the client secret. */
class DeviceTransportTest {

    private static final Predicate<String> HOST_PATH = path -> path.startsWith("/api/dispatcher/host");

    @Test
    void origin_is_fixed_and_tls_redirects_stay_strict() throws Exception {
        assertTrue(HttpClient.Redirect.NEVER == DeviceTransport.REDIRECTS);
        DeviceTransport.requireFixed(URI.create("https://app.freedriver.io/api/dispatcher/host/report"), HOST_PATH);
        DeviceTransport.requireFixed(DeviceTransport.TOKEN_ENDPOINT, HOST_PATH);
        assertThrows(IllegalArgumentException.class,
                () -> DeviceTransport.requireFixed(URI.create("http://app.freedriver.io/api/dispatcher/host/report"), HOST_PATH));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceTransport.requireFixed(URI.create("https://evil.example/api/dispatcher/host/report"), HOST_PATH));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceTransport.requireFixed(URI.create("https://app.freedriver.io/api/dispatcher/jobs"), HOST_PATH));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceTransport.requireFixed(URI.create("https://app.freedriver.io/api/device/register"), HOST_PATH));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceTransport.requireFixed(URI.create("https://user:pass@app.freedriver.io/api/dispatcher/host/report"), HOST_PATH));
        assertThrows(IllegalArgumentException.class,
                () -> DeviceTransport.requireFixed(URI.create("https://app.freedriver.io/api/dispatcher/host/report?access_token=x"), HOST_PATH));

        for (String refused : List.of(
                "https://app.freedriver.io/api/dispatcher/host/../jobs",
                "https://app.freedriver.io/api/dispatcher/host/./report",
                "https://app.freedriver.io/api/dispatcher/host/%2e%2e/jobs",
                "https://app.freedriver.io/api/dispatcher%2Fhost/report",
                "https://app.freedriver.io/api/dispatcher/host/%252e%252e/report")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DeviceTransport.requireFixed(URI.create(refused), HOST_PATH), refused);
        }

        String transport = Files.readString(Path.of("src/main/java/io/freedriver/daemon/DeviceTransport.java"));
        assertTrue(transport.contains("https://app.freedriver.io"));
        assertTrue(transport.contains("followRedirects(REDIRECTS)"));
        assertFalse(transport.contains("/api/dispatcher"));
        assertFalse(transport.contains("/api/device"));
        assertFalse(transport.contains("TrustAll"));
        assertFalse(transport.contains("InsecureTrustManager"));
    }

    @Test
    void form_encodes_the_secret() {
        assertTrue(DeviceTransport.form("pc.luna", "a+b/c=").contains("client_secret=a%2Bb%2Fc%3D"));
    }
}
