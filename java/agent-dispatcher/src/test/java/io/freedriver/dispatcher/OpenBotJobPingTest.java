package io.freedriver.dispatcher;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OpenBotJobPingTest {

    private static final String SECRET = "test-secret";
    private static final String PROMPT = "do not send this prompt";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T13:00:00Z"), ZoneOffset.UTC);

    @Test
    void posts_a_signed_body_without_the_prompt() throws Exception {
        List<Posted> posts = new CopyOnWriteArrayList<>();
        HttpServer server = server(posts, 202);
        server.start();
        try {
            OpenBotJobPing ping = ping(server);
            ping.queued("shi\"zuku");
            assertEquals(1, posts.size());
            Posted posted = posts.get(0);
            String body = new String(posted.body, StandardCharsets.UTF_8);
            assertFalse(body.contains(PROMPT));
            assertFalse(body.contains("prompt"));
            assertFalse(body.contains(SECRET));
            assertEquals(
                    "{\"type\":\"dispatch-job\",\"occurredAt\":\"2026-11-10T13:00:00Z\",\"data\":{\"hostname\":\"shi\\\"zuku\"}}",
                    body);
            assertEquals("application/json", posted.contentType);
            assertEquals(Long.toString(CLOCK.instant().getEpochSecond()), posted.timestamp);
            assertTrue(posted.deliveryId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"));
            assertEquals(expectedSignature(posted), posted.signature);
            assertFalse(posted.signature.contains(SECRET));
            assertFalse(posted.headersContainSecret);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unset_secret_skips_the_post() throws Exception {
        List<Posted> posts = new CopyOnWriteArrayList<>();
        HttpServer server = server(posts, 202);
        server.start();
        try {
            URI url = uri(server);
            new OpenBotJobPing("", url, CLOCK).queued("shizuku");
            new OpenBotJobPing("   ", url, CLOCK).queued("shizuku");
            new OpenBotJobPing(null, url, CLOCK).queued("shizuku");
            assertEquals(0, posts.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_failed_post_does_not_escape() throws Exception {
        List<Posted> posts = new CopyOnWriteArrayList<>();
        HttpServer server = server(posts, 503);
        server.start();
        try {
            ping(server).queued("shizuku");
            ping(server).queued("  ");
            assertEquals(1, posts.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void public_http_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new OpenBotJobPing(
                SECRET, URI.create("http://example.com/hook"), CLOCK));
        assertEquals(
                "https://signal.openbot.run/v1/webhooks/0ca403fc-5874-407c-8e70-38c518d442ee",
                OpenBotJobPing.WEBHOOK.toString());
    }

    private static OpenBotJobPing ping(HttpServer server) {
        return new OpenBotJobPing(SECRET, uri(server), CLOCK);
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
    }

    private static HttpServer server(List<Posted> posts, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String secretHeader = "no";
            for (String value : exchange.getRequestHeaders().values().stream().flatMap(List::stream).toList()) {
                if (value != null && value.contains(SECRET)) {
                    secretHeader = "yes";
                }
            }
            posts.add(new Posted(
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("x-openbot-timestamp"),
                    exchange.getRequestHeaders().getFirst("x-openbot-delivery-id"),
                    exchange.getRequestHeaders().getFirst("x-openbot-signature"),
                    body,
                    "yes".equals(secretHeader)));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        return server;
    }

    private static String expectedSignature(Posted posted) throws Exception {
        byte[] prefix = (posted.timestamp + "." + posted.deliveryId + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[prefix.length + posted.body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(posted.body, 0, message, prefix.length, posted.body.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(message)) {
            hex.append(String.format("%02x", b));
        }
        return "sha256=" + hex;
    }

    private record Posted(
            String contentType,
            String timestamp,
            String deliveryId,
            String signature,
            byte[] body,
            boolean headersContainSecret) {
    }
}
