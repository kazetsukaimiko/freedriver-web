package io.freedriver.sms.client;

import com.sun.net.httpserver.HttpServer;
import io.freedriver.sms.model.AgreementShown;
import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SendType;
import io.freedriver.sms.model.VerifyRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsClientTest {

    private static final String SECRET = "test-only-shared-secret";
    private static final String PHONE = "+15555550100";

    @Test
    void send_and_verify_use_the_contract_and_hide_the_secret() throws Exception {
        AtomicReference<String> sawSecret = new AtomicReference<>();
        AtomicReference<String> sawBody = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        try (Harness server = Harness.start((exchange, path) -> {
            hits.incrementAndGet();
            sawSecret.set(exchange.getRequestHeaders().getFirst(SmsClientConfig.HEADER));
            sawBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = "/sms/verify".equals(path)
                    ? "{\"verified\":{\"type\":\"otp\",\"phone\":\"" + PHONE + "\"}}"
                    : "{\"sent\":{\"type\":\"otp\",\"phone\":\"" + PHONE + "\"}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        })) {
            SmsClient client = client(server);
            var sent = client.send(new OtpSendRequest(PHONE, null));
            var verified = client.verify(new VerifyRequest(PHONE, "123456"));
            assertEquals(SendType.OTP, sent.sent().type());
            assertEquals(PHONE, sent.sent().phone());
            assertEquals(PHONE, verified.verified().phone());
            assertEquals(2, hits.get());
            assertEquals(SECRET, sawSecret.get());
            assertTrue(sawBody.get().contains("\"phone\":\"" + PHONE + "\""));
            assertFalse(sawBody.get().contains("\"agreement\""));
            assertFalse(client.toString().contains(SECRET));
            assertFalse(new SmsClientConfig(server.base(), SECRET).toString().contains(SECRET));
            assertTrue(java.lang.reflect.Proxy.isProxyClass(client.getClass()));
        }
    }

    @Test
    void error_status_keeps_the_token_and_drops_the_body() throws Exception {
        try (Harness server = Harness.start((exchange, path) -> {
            byte[] bytes = "{\"error\":\"invalid-code\",\"phone\":\"+15555550199\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        })) {
            SmsClientException ex = assertThrows(SmsClientException.class,
                    () -> client(server).verify(new VerifyRequest(PHONE, "000000")));
            assertEquals(400, ex.status());
            assertEquals("invalid-code", ex.error());
            assertFalse(ex.getMessage().contains(SECRET));
            assertFalse(ex.getMessage().contains(PHONE));
            assertFalse(ex.getMessage().contains("{"));
            assertFalse(ex.toString().contains("+15555550199"));
        }
    }

    @Test
    void retry_after_is_a_number_or_absent() throws Exception {
        try (Harness server = Harness.start((exchange, path) -> {
            exchange.getResponseHeaders().add("Retry-After", "900");
            byte[] bytes = "{\"error\":\"rate-limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        })) {
            SmsClientException ex = assertThrows(SmsClientException.class,
                    () -> client(server).send(new OtpSendRequest(PHONE, new AgreementShown("x"))));
            assertEquals(429, ex.status());
            assertEquals(900L, ex.retryAfterSeconds());
            assertFalse(ex.getMessage().contains("Retry-After"));
        }
    }

    @Test
    void http_date_retry_after_is_not_copied() throws Exception {
        try (Harness server = Harness.start((exchange, path) -> {
            exchange.getResponseHeaders().add("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT");
            byte[] bytes = "{\"error\":\"rate-limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        })) {
            SmsClientException ex = assertThrows(SmsClientException.class,
                    () -> client(server).send(new OtpSendRequest(PHONE, null)));
            assertNull(ex.retryAfterSeconds());
            assertFalse(ex.getMessage().contains("Wed"));
            assertFalse(ex.getMessage().contains("GMT"));
        }
    }

    @Test
    void redirects_are_not_followed() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Harness server = Harness.start((exchange, path) -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", serverBase(exchange) + "/stolen");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        })) {
            SmsClientException ex = assertThrows(SmsClientException.class,
                    () -> client(server).send(new OtpSendRequest(PHONE, null)));
            assertEquals(302, ex.status());
            assertEquals(1, hits.get());
            assertFalse(ex.getMessage().contains("stolen"));
            assertFalse(ex.getMessage().contains("Location"));
        }
    }

    @Test
    void empty_placeholder_and_hostile_secrets_are_not_sent() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Harness server = Harness.start((exchange, path) -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        })) {
            for (String secret : new String[] {null, "", "  ", SmsClientConfig.PLACEHOLDER, "bad\r\nX", "has space", "a".repeat(257)}) {
                SmsClient client = SmsClient.create(new SmsClientConfig(server.base(), secret));
                SmsClientException ex = assertThrows(SmsClientException.class,
                        () -> client.send(new OtpSendRequest(PHONE, null)));
                assertEquals(401, ex.status());
                if (secret != null) {
                    assertFalse(ex.getMessage().contains(secret.trim().isEmpty() ? "unused" : secret));
                }
            }
            assertEquals(0, hits.get());
        }
    }

    @Test
    void disallowed_urls_are_not_contacted() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Harness server = Harness.start((exchange, path) -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        })) {
            URI[] rejected = {
                    URI.create("http://192.0.2.1:8080"),
                    URI.create("https://sms:8080"),
                    URI.create("http://sms:8081"),
                    URI.create("http://127.0.0.1:8080"),
                    URI.create("http://user:" + SECRET + "@127.0.0.1:" + server.port()),
                    URI.create(server.base() + "?x=1"),
                    URI.create("http://localhost:" + server.port())
            };
            for (URI base : rejected) {
                SmsClient client = SmsClient.create(new SmsClientConfig(base, SECRET));
                SmsClientException ex = assertThrows(SmsClientException.class,
                        () -> client.send(new OtpSendRequest(PHONE, null)));
                assertEquals(0, ex.status());
                assertEquals("unavailable", ex.error());
                assertFalse(ex.getMessage().contains(SECRET));
            }
            assertEquals(0, hits.get());
        }
    }

    @Test
    void timeout_does_not_wait_for_the_body() throws Exception {
        try (Harness server = Harness.start((exchange, path) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        })) {
            SmsClient client = SmsClients.create(new SmsClientConfig(server.base(), SECRET), Duration.ofMillis(200));
            long started = System.nanoTime();
            SmsClientException ex = assertThrows(SmsClientException.class,
                    () -> client.send(new OtpSendRequest(PHONE, null)));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertEquals(0, ex.status());
            assertEquals("unavailable", ex.error());
            assertTrue(elapsedMs < 2_000, "elapsed " + elapsedMs);
            assertFalse(ex.getMessage().contains(PHONE));
        }
    }

    private static SmsClient client(Harness server) {
        return SmsClient.create(new SmsClientConfig(server.base(), SECRET));
    }

    private static String serverBase(com.sun.net.httpserver.HttpExchange exchange) {
        return "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
    }

    @FunctionalInterface
    interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, String path) throws java.io.IOException;
    }

    private static final class Harness implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private Harness(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static Harness start(Handler handler) throws java.io.IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "sms-client-test");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/", exchange -> handler.handle(exchange, exchange.getRequestURI().getPath()));
            server.start();
            return new Harness(server, executor);
        }

        URI base() {
            return URI.create("http://127.0.0.1:" + port());
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
