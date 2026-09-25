package io.freedriver.keycloak.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        UNAVAILABLE
    }

    public record Result(Outcome outcome, String username) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private static final int MAX_BODY = 8192;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** HTTP status and response body. */
    record Wire(int status, String body) {}

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
        return call(SmsOtpConfig.BASE_URL, "/otp/send", secret, jsonPhone(phone), false);
    }

    public Result verify(String secret, String phone, String code) {
        return call(SmsOtpConfig.BASE_URL, "/otp/verify", secret, jsonVerify(phone, code), true);
    }

    /** Package-visible so tests can point at loopback. Production send/verify use {@link SmsOtpConfig#BASE_URL}. */
    Result call(URI base, String path, String secret, String json, boolean expectUsername) {
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
            return interpret(wire.status(), wire.body(), expectUsername);
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
        return new Wire(response.statusCode(), response.body());
    }

    static Result interpret(int status, String body, boolean expectUsername) {
        if (body != null && body.length() > MAX_BODY) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        if (status == 401 || status == 403) {
            return Result.of(Outcome.REJECTED);
        }
        if (expectUsername && status == 400 && "invalid-code".equals(textField(body, "error"))) {
            return Result.of(Outcome.INVALID_CODE);
        }
        if (status != 200) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        if (expectUsername) {
            String username = textField(body, "username");
            if (username == null || !usernameAllowed(username)) {
                return Result.of(Outcome.UNAVAILABLE);
            }
            return new Result(Outcome.VERIFIED, username);
        }
        if (!"sent".equals(textField(body, "status"))) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        return Result.of(Outcome.SENT);
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

    static boolean usernameAllowed(String username) {
        if (username.isEmpty() || username.length() > 128) {
            return false;
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '@' || c == '+' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    static String jsonPhone(String phone) {
        return JSON.createObjectNode().put("phone", phone).toString();
    }

    static String jsonVerify(String phone, String code) {
        return JSON.createObjectNode().put("phone", phone).put("code", code).toString();
    }

    /** Text value of a top-level string field, or null for any other body. */
    static String textField(String body, String field) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode value = root.get(field);
            return value != null && value.isTextual() ? value.textValue() : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
