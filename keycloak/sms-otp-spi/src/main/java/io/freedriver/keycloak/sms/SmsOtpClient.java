package io.freedriver.keycloak.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for the sms service. Success is a 200 with the expected JSON.
 * The shared secret travels in the X-Freedriver-Sms-Secret header.
 * JSON goes through the Jackson that the Keycloak server provides.
 */
public final class SmsOtpClient {

    public enum Outcome {
        SENT,
        VERIFIED,
        INVALID_CODE,
        REJECTED,
        RATE_LIMITED,
        /** Verify answered 503 {@code unavailable}. */
        CHECK_UNAVAILABLE,
        UNAVAILABLE
    }

    /** Per-number window the sms service sends as {@code Retry-After: 900}. */
    static final int DEFAULT_RETRY_AFTER_SECONDS = 900;
    static final int MAX_RETRY_AFTER_SECONDS = 3600;

    public record Result(Outcome outcome, String phone, Integer retryAfterSeconds) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null, null);
        }

        static Result rateLimited(String header) {
            return new Result(Outcome.RATE_LIMITED, null, parseRetryAfter(header));
        }
    }

    private static final int MAX_BODY = 8192;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** HTTP status, response body, and the Retry-After header when present. */
    record Wire(int status, String body, String retryAfter) {
        Wire(int status, String body) {
            this(status, body, null);
        }
    }

    @FunctionalInterface
    interface Poster {
        Wire post(URI uri, String secret, String json) throws IOException, InterruptedException;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Poster poster;

    public SmsOtpClient() {
        this(SmsOtpClient::postHttp);
    }

    SmsOtpClient(Poster poster) {
        this.poster = poster;
    }

    public Result send(String secret, String phone) {
        return call(SmsOtpConfig.BASE_URL, "/sms/send", secret, jsonSend(phone, SignInAgreement.WORDING), false);
    }

    public Result verify(String secret, String phone, String code) {
        return call(SmsOtpConfig.BASE_URL, "/sms/verify", secret, jsonVerify(phone, code), true);
    }

    /** Package-visible so tests can point at loopback. Production send/verify use {@link SmsOtpConfig#BASE_URL}. */
    Result call(URI base, String path, String secret, String json, boolean expectVerified) {
        if (SmsOtpConfig.normalize(secret).isEmpty()) {
            return Result.of(Outcome.REJECTED);
        }
        if (!allowed(base)) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        URI uri = base.resolve(path);
        if (!allowed(uri) || !path.equals(uri.getPath())) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        try {
            Wire wire = poster.post(uri, secret, json);
            return interpret(wire.status(), wire.body(), wire.retryAfter(), expectVerified);
        } catch (IOException ex) {
            return Result.of(Outcome.UNAVAILABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.of(Outcome.UNAVAILABLE);
        }
    }

    private static Wire postHttp(URI uri, String secret, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(SmsOtpConfig.HEADER, secret)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        return new Wire(response.statusCode(), response.body(), retryAfter);
    }

    static Result interpret(int status, String body, String retryAfter, boolean expectVerified) {
        if (body != null && body.length() > MAX_BODY) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        if (status == 401 || status == 403) {
            return Result.of(Outcome.REJECTED);
        }
        if (status == 429) {
            return Result.rateLimited(retryAfter);
        }
        if (expectVerified && status == 400 && "invalid-code".equals(textField(body, "error"))) {
            return Result.of(Outcome.INVALID_CODE);
        }
        if (expectVerified && status == 503 && "unavailable".equals(textField(body, "error"))) {
            return Result.of(Outcome.CHECK_UNAVAILABLE);
        }
        if (status != 200) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        String object = expectVerified ? "verified" : "sent";
        String type = objectText(body, object, "type");
        String phone = objectText(body, object, "phone");
        if (!"otp".equals(type) || phone == null || phone.isEmpty()) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        return new Result(expectVerified ? Outcome.VERIFIED : Outcome.SENT, phone, null);
    }

    /**
     * Seconds to wait. A whole number from 1 to 3600 is used as-is.
     * Anything else, including a missing header or an HTTP-date, is the fixed default.
     */
    static int parseRetryAfter(String header) {
        if (header == null) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        String trimmed = header.trim();
        if (!trimmed.matches("[0-9]{1,10}")) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        if (value < 1 || value > MAX_RETRY_AFTER_SECONDS) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        return (int) value;
    }

    static boolean allowed(URI uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        int port = uri.getPort();
        String host = uri.getHost();
        if ("sms".equals(host) && port == 8080) {
            return true;
        }
        // Loopback serves the unit test server; send/verify use BASE_URL.
        return "127.0.0.1".equals(host) && port > 0 && port != 8080;
    }

    static String jsonSend(String phone, String wording) {
        ObjectNode body = JSON.createObjectNode();
        body.put("type", "otp");
        body.put("phone", phone);
        body.putObject("agreement").put("wording", wording);
        return body.toString();
    }

    static String jsonVerify(String phone, String code) {
        return JSON.createObjectNode().put("phone", phone).put("code", code).toString();
    }

    /** Text value of a top-level string field, or null for any other body. */
    static String textField(String body, String field) {
        JsonNode root = object(body);
        if (root == null) {
            return null;
        }
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    /** Text value of a string field on a top-level object, or null. */
    static String objectText(String body, String object, String field) {
        JsonNode root = object(body);
        if (root == null) {
            return null;
        }
        JsonNode nested = root.get(object);
        if (nested == null || !nested.isObject()) {
            return null;
        }
        JsonNode value = nested.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static JsonNode object(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
