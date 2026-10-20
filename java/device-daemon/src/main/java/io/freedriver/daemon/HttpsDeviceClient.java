package io.freedriver.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.RegisterDeviceRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Device client. The API origin and the token endpoint are fixed. TLS verification
 * stays on, redirects stay off, and a config file cannot point the token at another host.
 */
final class HttpsDeviceClient implements DeviceApiClient {

    static final URI API_ROOT = URI.create("https://app.freedriver.io");
    static final URI TOKEN_ENDPOINT = URI.create(
            "https://auth.freedriver.io/realms/freedriver/protocol/openid-connect/token");
    static final HttpClient.Redirect REDIRECTS = HttpClient.Redirect.NEVER;
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private static final String REGISTER = "/api/device/register";
    private static final String HEARTBEAT = "/api/device/heartbeat";
    private static final String ASSIGNMENTS = "/api/device/assignments";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new ParameterNamesModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private final String clientId;
    private final String secret;
    private final String rawSecret;
    private final HttpSender sender;
    private final Clock clock;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    static HttpsDeviceClient create(String clientId, SecretFile.Material material) {
        return new HttpsDeviceClient(
                clientId,
                material.secret(),
                material.raw(),
                new JdkHttpSender(newVerifyingClient()),
                Clock.systemUTC());
    }

    HttpsDeviceClient(String clientId, String secret, String rawSecret, HttpSender sender, Clock clock) {
        this.clientId = clientId;
        this.secret = secret;
        this.rawSecret = rawSecret;
        this.sender = sender;
        this.clock = clock;
    }

    static HttpClient newVerifyingClient() {
        return HttpClient.newBuilder()
                .followRedirects(REDIRECTS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    static void requireFixed(URI uri) {
        if (uri == null || uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("device api requires https");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("device api rejects user info and query");
        }
        String host = uri.getHost();
        if ("auth.freedriver.io".equals(host)) {
            if (!"/realms/freedriver/protocol/openid-connect/token".equals(uri.getPath())) {
                throw new IllegalArgumentException("unexpected token path");
            }
            return;
        }
        if (!"app.freedriver.io".equals(host)) {
            throw new IllegalArgumentException("device api host is fixed");
        }
        String path = uri.getPath();
        if (path == null || path.startsWith("/api/dispatcher") || !path.startsWith("/api/device/")) {
            throw new IllegalArgumentException("device api path is fixed");
        }
    }

    @Override
    public void register(String hostname, List<String> agents) {
        exchange(token -> json("POST", REGISTER, token, new RegisterDeviceRequest(hostname, agents), CALL_TIMEOUT));
    }

    @Override
    public void heartbeat() {
        exchange(token -> noBody("POST", HEARTBEAT, token, CALL_TIMEOUT));
    }

    @Override
    public List<AssignmentView> poll() {
        HttpResponse<String> response = exchange(token -> noBody("GET", ASSIGNMENTS, token, POLL_TIMEOUT));
        List<AssignmentView> views = read(response.body(), new TypeReference<List<AssignmentView>>() {
        });
        return views == null ? List.of() : views;
    }

    @Override
    public AssignmentView claim(UUID assignmentId) {
        HttpResponse<String> response = exchange(token -> noBody(
                "POST", ASSIGNMENTS + "/" + assignmentId + "/claim", token, CALL_TIMEOUT));
        return read(response.body(), AssignmentView.class);
    }

    @Override
    public AssignmentView assignment(UUID assignmentId) {
        HttpResponse<String> response = exchange(token -> noBody(
                "GET", ASSIGNMENTS + "/" + assignmentId, token, CALL_TIMEOUT));
        return read(response.body(), AssignmentView.class);
    }

    @Override
    public AssignmentView update(UUID assignmentId, UpdateAssignmentRequest request) {
        HttpResponse<String> response = exchange(token -> {
            String note = Notes.forUpdate(request.note(), secret, rawSecret, token);
            UpdateAssignmentRequest safe = new UpdateAssignmentRequest(request.state(), request.grokSessionId(), note);
            return json("POST", ASSIGNMENTS + "/" + assignmentId, token, safe, CALL_TIMEOUT);
        });
        return read(response.body(), AssignmentView.class);
    }

    synchronized String currentToken() {
        return accessToken;
    }

    private HttpResponse<String> exchange(Function<String, HttpRequest> request) {
        String token = bearer(false);
        HttpResponse<String> response = sendChecked(request.apply(token));
        if (response.statusCode() == 401) {
            token = bearer(true);
            response = sendChecked(request.apply(token));
        }
        if (response.statusCode() / 100 != 2) {
            throw new DeviceRejected(response.statusCode());
        }
        return response;
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
        requireFixed(TOKEN_ENDPOINT);
        return HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(CALL_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(clientId, secret), StandardCharsets.UTF_8))
                .build();
    }

    static String form(String clientId, String secret) {
        return "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    }

    private HttpRequest json(String method, String path, String token, Object body, Duration timeout) {
        URI uri = API_ROOT.resolve(path);
        requireFixed(uri);
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

    private HttpRequest noBody(String method, String path, String token, Duration timeout) {
        URI uri = API_ROOT.resolve(path);
        requireFixed(uri);
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpResponse<String> sendChecked(HttpRequest request) {
        requireFixed(request.uri());
        try {
            return sender.send(request);
        } catch (IOException failed) {
            throw new IllegalStateException("device call failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("device call interrupted");
        }
    }

    private static <T> T read(String body, Class<T> type) {
        try {
            return JSON.readValue(body, type);
        } catch (IOException failed) {
            throw new IllegalStateException("device response was not usable");
        }
    }

    private static <T> T read(String body, TypeReference<T> type) {
        try {
            T value = JSON.readValue(body, type);
            return value == null ? null : value;
        } catch (IOException failed) {
            throw new IllegalStateException("device response was not usable");
        }
    }
}
