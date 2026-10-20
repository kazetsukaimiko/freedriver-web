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

    private static final String PHONE = "+15555550100";

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
            return new SmsOtpClient.Wire(200, sent(PHONE));
        });
        assertEquals(SmsOtpClient.Outcome.REJECTED,
                client.send(SmsOtpConfig.PLACEHOLDER, PHONE).outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED, client.send("  ", PHONE).outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED, client.send(null, PHONE).outcome());
        assertEquals(0, hits.get());
    }

    @Test
    void disallowedHostIsNotContacted() {
        AtomicInteger hits = new AtomicInteger();
        SmsOtpClient client = new SmsOtpClient((uri, secret, json) -> {
            hits.incrementAndGet();
            return new SmsOtpClient.Wire(200, sent(PHONE));
        });
        SmsOtpClient.Result result = client.call(
                URI.create("http://sms.freedriver.io:8080"),
                "/sms/send",
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
            byte[] body = sent(PHONE).getBytes(StandardCharsets.UTF_8);
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
                    "/sms/send",
                    "real-secret",
                    SmsOtpClient.jsonSend(PHONE, SignInAgreement.WORDING),
                    false);
            assertEquals(SmsOtpClient.Outcome.SENT, result.outcome());
            assertEquals(PHONE, result.phone());
            assertEquals("/sms/send", seenPath.get());
            assertEquals("real-secret", seenHeader.get());
            assertFalse(seenBody.get().contains("real-secret"));
            assertTrue(seenBody.get().contains(PHONE));
            assertTrue(seenBody.get().contains("\"type\":\"otp\""));
            assertTrue(seenBody.get().contains(SignInAgreement.WORDING));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendAndVerifyUseTheSmsContract() {
        SmsOtpClient.Result send = SmsOtpClient.interpret(200, sent(PHONE), null, false);
        assertEquals(SmsOtpClient.Outcome.SENT, send.outcome());
        assertEquals(PHONE, send.phone());

        SmsOtpClient.Result ok = SmsOtpClient.interpret(200, verified(PHONE), null, true);
        assertEquals(SmsOtpClient.Outcome.VERIFIED, ok.outcome());
        assertEquals(PHONE, ok.phone());

        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(200, verified(PHONE), null, false).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(200, sent(PHONE), null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(200, "{\"username\":\"house.user\"}", null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED,
                SmsOtpClient.interpret(401, "{\"error\":\"unauthorized\"}", null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.REJECTED,
                SmsOtpClient.interpret(403, "{\"error\":\"no\"}", null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.CHECK_UNAVAILABLE,
                SmsOtpClient.interpret(503, "{\"error\":\"unavailable\"}", null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(503, "{\"error\":\"unavailable\"}", null, false).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(503, "{\"error\":\"stub\"}", null, true).outcome());
    }

    @Test
    void wrongCodeIsItsOwnOutcome() {
        assertEquals(SmsOtpClient.Outcome.INVALID_CODE,
                SmsOtpClient.interpret(400, "{\"error\":\"invalid-code\"}", null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(400, "{\"error\":\"bad-request\"}", null, true).outcome());
        assertEquals(SmsOtpClient.Outcome.UNAVAILABLE,
                SmsOtpClient.interpret(400, "{\"error\":\"invalid-code\"}", null, false).outcome());
    }

    @Test
    void retryAfterUsesAWholeNumberOfSecondsOrTheDefault() {
        assertEquals(900, SmsOtpClient.interpret(429, "{\"error\":\"rate-limited\"}", "900", false).retryAfterSeconds());
        assertEquals(1, SmsOtpClient.interpret(429, "{}", "1", true).retryAfterSeconds());
        assertEquals(3600, SmsOtpClient.interpret(429, "{}", "3600", false).retryAfterSeconds());
        assertEquals(60, SmsOtpClient.interpret(429, "{}", " 60 ", false).retryAfterSeconds());
        assertEquals(SmsOtpClient.Outcome.RATE_LIMITED,
                SmsOtpClient.interpret(429, "{\"error\":\"rate-limited\"}", "900", true).outcome());
        for (String bad : new String[] {null, "", "0", "3601", "900.0", "+60",
                "Wed, 21 Oct 2015 07:28:00 GMT", "60s", "1 2"}) {
            assertEquals(SmsOtpClient.DEFAULT_RETRY_AFTER_SECONDS,
                    SmsOtpClient.interpret(429, "{}", bad, false).retryAfterSeconds(), bad);
        }
    }

    @Test
    void jsonGoesThroughJackson() {
        String send = SmsOtpClient.jsonSend(PHONE, SignInAgreement.WORDING);
        assertEquals("{\"type\":\"otp\",\"phone\":\"" + PHONE + "\",\"agreement\":{\"wording\":\""
                + SignInAgreement.WORDING + "\"}}", send);
        assertTrue(SmsOtpClient.jsonSend("a\"b", "x\"y").contains("\"phone\":\"a\\\"b\""));
        String body = SmsOtpClient.jsonVerify(PHONE, "123456");
        assertEquals("{\"phone\":\"+15555550100\",\"code\":\"123456\"}", body);
        assertEquals(PHONE, SmsOtpClient.textField(body, "phone"));
        assertEquals("A", SmsOtpClient.textField("{\"username\":\"\\u0041\"}", "username"));
        assertEquals("real", SmsOtpClient.textField("{\"note\":\"\\\"username\\\":\\\"fake\\\"\",\"username\":\"real\"}", "username"));
        assertNull(SmsOtpClient.textField("{\"username\":42}", "username"));
        assertNull(SmsOtpClient.textField("[\"username\"]", "username"));
        assertNull(SmsOtpClient.textField("{\"username\":", "username"));
        assertNull(SmsOtpClient.textField(null, "username"));
        assertNull(SmsOtpClient.objectText(send, "sent", "phone"));
        assertEquals(PHONE, SmsOtpClient.objectText(sent(PHONE), "sent", "phone"));
        assertEquals("otp", SmsOtpClient.objectText(verified(PHONE), "verified", "type"));
    }

    private static String sent(String phone) {
        return "{\"sent\":{\"type\":\"otp\",\"phone\":\"" + phone + "\"}}";
    }

    private static String verified(String phone) {
        return "{\"verified\":{\"type\":\"otp\",\"phone\":\"" + phone + "\"}}";
    }
}
