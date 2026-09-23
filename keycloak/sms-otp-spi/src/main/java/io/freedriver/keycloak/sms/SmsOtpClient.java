package io.freedriver.keycloak.sms;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for the sms sibling. Fail closed: only 200 plus the expected
 * JSON counts, and the shared secret is a header, never the body or the URL.
 */
public final class SmsOtpClient {

    public enum Outcome {
        SENT,
        VERIFIED,
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

    /** Status and body only. The secret stays out of this record. */
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
        if (status != 200) {
            return Result.of(Outcome.UNAVAILABLE);
        }
        if (expectUsername) {
            String username = Json.stringField(body, "username");
            if (username == null || !usernameAllowed(username)) {
                return Result.of(Outcome.UNAVAILABLE);
            }
            return new Result(Outcome.VERIFIED, username);
        }
        if (!"sent".equals(Json.stringField(body, "status"))) {
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
        // Loopback is for the unit test server only. send/verify do not use it.
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
        return "{\"phone\":" + Json.quote(phone) + "}";
    }

    static String jsonVerify(String phone, String code) {
        return "{\"phone\":" + Json.quote(phone) + ",\"code\":" + Json.quote(code) + "}";
    }

    /** Flat JSON helpers. The SPI does not take a JSON library. */
    static final class Json {
        private Json() {}

        static String quote(String value) {
            StringBuilder out = new StringBuilder(value.length() + 2);
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '"' -> out.append("\\\"");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
            return out.toString();
        }

        static String stringField(String json, String field) {
            if (json == null) {
                return null;
            }
            String key = "\"" + field + "\"";
            int at = json.indexOf(key);
            if (at < 0) {
                return null;
            }
            int colon = json.indexOf(':', at + key.length());
            if (colon < 0) {
                return null;
            }
            int start = json.indexOf('"', colon + 1);
            if (start < 0) {
                return null;
            }
            StringBuilder value = new StringBuilder();
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\') {
                    if (i + 1 >= json.length()) {
                        return null;
                    }
                    char next = json.charAt(++i);
                    if (next == 'u') {
                        if (i + 4 >= json.length()) {
                            return null;
                        }
                        int cp;
                        try {
                            cp = Integer.parseInt(json.substring(i + 1, i + 5), 16);
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                        value.append((char) cp);
                        i += 4;
                        continue;
                    }
                    value.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> next;
                    });
                    continue;
                }
                if (c == '"') {
                    return value.toString();
                }
                value.append(c);
            }
            return null;
        }
    }
}
