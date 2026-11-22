package io.freedriver.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The transport both dispatcher clients share: the fixed origin and token endpoint, the
 * client-credentials token from the secret file, and the 401 repeat. TLS verification stays on and
 * redirects stay off. Which paths a client may call is that client's own allow-list; this class
 * refuses dot segments and encoded dots or slashes before it asks that list.
 */
final class DeviceTransport {

    static final URI API_ROOT = URI.create("https://app.freedriver.io");
    static final URI TOKEN_ENDPOINT = URI.create(
            "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/token");
    static final HttpClient.Redirect REDIRECTS = HttpClient.Redirect.NEVER;
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new ParameterNamesModule())
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private static final String API_HOST = "app.freedriver.io";
    private static final String AUTH_HOST = "auth.freedriver.io";
    private static final String TOKEN_PATH = "/realms/freedriver/protocol/openid-connect/token";

    private final String clientId;
    private final String secret;
    private final String rawSecret;
    private final HttpSender sender;
    private final Clock clock;
    private final Predicate<String> allowedPath;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    DeviceTransport(
            String clientId,
            String secret,
            String rawSecret,
            HttpSender sender,
            Clock clock,
            Predicate<String> allowedPath) {
        this.clientId = clientId;
        this.secret = secret;
        this.rawSecret = rawSecret;
        this.sender = sender;
        this.clock = clock;
        this.allowedPath = allowedPath;
    }

    static HttpClient newVerifyingClient() {
        return HttpClient.newBuilder()
                .followRedirects(REDIRECTS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Refuses anything but https to the fixed token endpoint, or https to the fixed API host on a
     * path the client allows. Dot segments and encoded dots or slashes are refused first, in the
     * raw path and in the decoded path.
     */
    static void requireFixed(URI uri, Predicate<String> allowedPath) {
        if (uri == null || uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("device api requires https");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("device api rejects user info and query");
        }
        if (uri.getPort() != -1) {
            throw new IllegalArgumentException("device api port is fixed");
        }
        requireNoDotSegments(uri.getRawPath());
        String host = uri.getHost();
        if (AUTH_HOST.equals(host)) {
            if (!TOKEN_PATH.equals(uri.getRawPath())) {
                throw new IllegalArgumentException("unexpected token path");
            }
            return;
        }
        if (!API_HOST.equals(host)) {
            throw new IllegalArgumentException("device api host is fixed");
        }
        if (!allowedPath.test(uri.getRawPath())) {
            throw new IllegalArgumentException("device api path is fixed");
        }
    }

    static void requireNoDotSegments(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            throw new IllegalArgumentException("device api path is fixed");
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("device api path is not usable");
        }
        for (String path : List.of(rawPath, decoded)) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("\\")) {
                throw new IllegalArgumentException("device api path is not usable");
            }
            for (String segment : path.split("/", -1)) {
                if (".".equals(segment) || "..".equals(segment)) {
                    throw new IllegalArgumentException("device api path is not usable");
                }
            }
        }
    }

    /** The values a note or a log line must never carry: the secret, the raw file and the token. */
    synchronized List<String> forbidden() {
        List<String> values = new ArrayList<>();
        values.add(secret);
        values.add(rawSecret);
        if (accessToken != null) {
            values.add(accessToken);
        }
        return values;
    }

    synchronized String currentToken() {
        return accessToken;
    }

    /**
     * Sends with the cached token; on 401 fetches a new token and sends once more. The status is
     * left to the caller.
     */
    HttpResponse<String> exchange(Function<String, HttpRequest> request) {
        String token = bearer(false);
        HttpResponse<String> response = sendChecked(request.apply(token));
        if (response.statusCode() == 401) {
            token = bearer(true);
            response = sendChecked(request.apply(token));
        }
        return response;
    }

    HttpRequest json(String method, String path, String token, Object body, Duration timeout) {
        URI uri = API_ROOT.resolve(path);
        requireFixed(uri, allowedPath);
        String encoded;
        try {
            encoded = JSON.writeValueAsString(body);
        } catch (JsonProcessingException failed) {
            throw new IllegalStateException("device request was not usable");
        }
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
                .build();
    }

    HttpRequest noBody(String method, String path, String token, Duration timeout) {
        URI uri = API_ROOT.resolve(path);
        requireFixed(uri, allowedPath);
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }

    String secret() {
        return secret;
    }

    String rawSecret() {
        return rawSecret;
    }

    static String form(String clientId, String secret) {
        return "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    }

    static <T> T read(String body, Class<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (IOException failed) {
            throw new IllegalStateException("device response was not usable");
        }
    }

    static <T> T read(String body, TypeReference<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (IOException failed) {
            throw new IllegalStateException("device response was not usable");
        }
    }

    private synchronized String bearer(boolean force) {
        if (!force && accessToken != null && clock.instant().isBefore(accessTokenExpiresAt)) {
            return accessToken;
        }
        HttpResponse<String> response = sendChecked(tokenRequest());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("token request failed");
        }
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (IOException failed) {
            throw new IllegalStateException("token response was not usable");
        }
        String token = node.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("token response was not usable");
        }
        long expiresIn = node.path("expires_in").asLong(30);
        accessToken = token;
        accessTokenExpiresAt = clock.instant().plusSeconds(Math.max(1, expiresIn - 15));
        return accessToken;
    }

    private HttpRequest tokenRequest() {
        requireFixed(TOKEN_ENDPOINT, allowedPath);
        return HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(CALL_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(clientId, secret), StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<String> sendChecked(HttpRequest request) {
        requireFixed(request.uri(), allowedPath);
        try {
            return sender.send(request);
        } catch (IOException failed) {
            throw new IllegalStateException("device call failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("device call interrupted");
        }
    }
}
