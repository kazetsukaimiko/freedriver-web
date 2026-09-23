package io.freedriver.keycloak.sms;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsOtpClientTest {

    @Test
    void composeUrlIsTheOnlyProductionTarget() {
        URI base = SmsOtpConfig.BASE_URL;
        assertEquals("http", base.getScheme());
        assertEquals("sms", base.getHost());
        assertEquals(8080, base.getPort());
        assertEquals("", base.getPath());
        assertTrue(SmsOtpClient.allowed(base));
        assertFalse(SmsOtpClient.allowed(URI.create("https://sms:8080")));
        assertFalse(SmsOtpClient.allowed(URI.create("http://sms:80")));
        assertFalse(SmsOtpClient.allowed(URI.create("http://sms.freedriver.io:8080")));
        assertFalse(SmsOtpClient.allowed(URI.create("http://user:secret@sms:8080")));
    }

    @Test
    void placeholderAndBlankSecretsDoNotCallSms() {
        AtomicInteger hits = new AtomicInteger();
        SmsOtpClient client = new SmsOtpClient((uri, secret, json) -> {
            hits.incrementAndGet();
            return new SmsOtpClient.Wire(200, "{\"status\":\"sent\"}");
        });
        assertEquals(SmsOtpClient.Outcome.REJECTED,
                client.send(SmsOtpConfig.PLACEHOLDER, "+15555550100").outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED, client.send("  ", "+15555550100").outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED, client.send(null, "+15555550100").outcome());
        assertEquals(0, hits.get());
    }

    @Test
    void disallowedHostIsNotContacted() {
        AtomicInteger hits = new AtomicInteger();
        SmsOtpClient client = new SmsOtpClient((uri, secret, json) -> {
            hits.incrementAndGet();
            return new SmsOtpClient.Wire(200, "{\"status\":\"sent\"}");
        });
        SmsOtpClient.Result result = client.call(
                URI.create("http://sms.freedriver.io:8080"),
                "/otp/send",
                "real-secret",
                "{}",
                false);
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE, result.outcome());
        assertEquals(0, hits.get());
    }

    @Test
    void httpContractFailsClosedAndKeepsTheSecretInTheHeader() throws Exception {
        AtomicReference<String> seenHeader = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenHeader.set(exchange.getRequestHeaders().getFirst(SmsOtpConfig.HEADER));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"status\":\"sent\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SmsOtpClient client = new SmsOtpClient();
            SmsOtpClient.Result result = client.call(
                    URI.create("http://127.0.0.1:" + port),
                    "/otp/send",
                    "real-secret",
                    SmsOtpClient.jsonPhone("+15555550100"),
                    false);
            assertEquals(SmsOtpClient.Outcome.SENT, result.outcome());
            assertEquals("/otp/send", seenPath.get());
            assertEquals("real-secret", seenHeader.get());
            assertFalse(seenBody.get().contains("real-secret"));
            assertTrue(seenBody.get().contains("+15555550100"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonSuccessStatusesDoNotVerify() {
        assertEquals(SmsOtpClient.Outcome.REJECTED,
                SmsOtpClient.interpret(401, "{\"error\":\"unauthorized\"}", true).outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED,
                SmsOtpClient.interpret(403, "{\"error\":\"no\"}", true).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(503, "{\"error\":\"stub\"}", true).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(200, "{\"status\":\"sent\"}", true).outcome());
        SmsOtpClient.Result ok = SmsOtpClient.interpret(200, "{\"username\":\"house.user\"}", true);
        assertEquals(SmsOtpClient.Outcome.VERIFIED, ok.outcome());
        assertEquals("house.user", ok.username());
        assertNull(SmsOtpClient.interpret(200, "{\"username\":\"has space\"}", true).username());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(200, "{\"status\":\"nope\"}", false).outcome());
    }

    @Test
    void jsonHelpersDoNotTreatTheSecretAsAField() {
        String body = SmsOtpClient.jsonVerify("+15555550100", "123456");
        assertFalse(body.contains("real-secret"));
        assertEquals("+15555550100", SmsOtpClient.Json.stringField(body, "phone"));
        assertEquals("123456", SmsOtpClient.Json.stringField(body, "code"));
        assertEquals("A", SmsOtpClient.Json.stringField("{\"username\":\"\\u0041\"}", "username"));
        assertEquals("a\"b", SmsOtpClient.Json.stringField("{\"username\":" + SmsOtpClient.Json.quote("a\"b") + "}", "username"));
    }
}
