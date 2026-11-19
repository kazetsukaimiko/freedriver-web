package io.freedriver.daemon;

import com.fasterxml.jackson.core.type.TypeReference;
import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.RegisterDeviceRequest;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Grok-mode device client. It sends only under {@code https://app.freedriver.io/api/device/} and
 * to the fixed token endpoint, and refuses every {@code /api/dispatcher} path. TLS verification
 * stays on, redirects stay off, and a config file cannot point the token at another host.
 */
final class HttpsDeviceClient implements DeviceApiClient {

    static final URI API_ROOT = DeviceTransport.API_ROOT;
    static final URI TOKEN_ENDPOINT = DeviceTransport.TOKEN_ENDPOINT;
    static final HttpClient.Redirect REDIRECTS = HttpClient.Redirect.NEVER;
    static final Duration POLL_TIMEOUT = Duration.ofSeconds(60);
    static final Duration CALL_TIMEOUT = DeviceTransport.CALL_TIMEOUT;

    private static final String REGISTER = "/api/device/register";
    private static final String HEARTBEAT = "/api/device/heartbeat";
    private static final String ASSIGNMENTS = "/api/device/assignments";

    private final DeviceTransport transport;

    static HttpsDeviceClient create(String clientId, SecretFile.Material material) {
        return new HttpsDeviceClient(
                clientId,
                material.secret(),
                material.raw(),
                new JdkHttpSender(newVerifyingClient()),
                Clock.systemUTC());
    }

    HttpsDeviceClient(String clientId, String secret, String rawSecret, HttpSender sender, Clock clock) {
        this.transport = new DeviceTransport(clientId, secret, rawSecret, sender, clock, HttpsDeviceClient::allowed);
    }

    static HttpClient newVerifyingClient() {
        return HttpClient.newBuilder()
                .followRedirects(REDIRECTS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    static void requireFixed(URI uri) {
        DeviceTransport.requireFixed(uri, HttpsDeviceClient::allowed);
    }

    private static boolean allowed(String path) {
        return path != null && !path.startsWith("/api/dispatcher") && path.startsWith("/api/device/");
    }

    @Override
    public void register(String hostname, List<String> agents) {
        exchange(token -> transport.json(
                "POST", REGISTER, token, new RegisterDeviceRequest(hostname, agents), CALL_TIMEOUT));
    }

    @Override
    public void heartbeat() {
        exchange(token -> transport.noBody("POST", HEARTBEAT, token, CALL_TIMEOUT));
    }

    @Override
    public List<AssignmentView> poll() {
        HttpResponse<String> response = exchange(token -> transport.noBody("GET", ASSIGNMENTS, token, POLL_TIMEOUT));
        List<AssignmentView> views = DeviceTransport.read(response.body(), new TypeReference<List<AssignmentView>>() {
        });
        return views == null ? List.of() : views;
    }

    @Override
    public AssignmentView claim(UUID assignmentId) {
        HttpResponse<String> response = exchange(token -> transport.noBody(
                "POST", ASSIGNMENTS + "/" + assignmentId + "/claim", token, CALL_TIMEOUT));
        return DeviceTransport.read(response.body(), AssignmentView.class);
    }

    @Override
    public AssignmentView assignment(UUID assignmentId) {
        HttpResponse<String> response = exchange(token -> transport.noBody(
                "GET", ASSIGNMENTS + "/" + assignmentId, token, CALL_TIMEOUT));
        return DeviceTransport.read(response.body(), AssignmentView.class);
    }

    @Override
    public AssignmentView update(UUID assignmentId, UpdateAssignmentRequest request) {
        HttpResponse<String> response = exchange(token -> {
            String note = Notes.forUpdate(request.note(), transport.secret(), transport.rawSecret(), token);
            UpdateAssignmentRequest safe = new UpdateAssignmentRequest(request.state(), request.grokSessionId(), note);
            return transport.json("POST", ASSIGNMENTS + "/" + assignmentId, token, safe, CALL_TIMEOUT);
        });
        return DeviceTransport.read(response.body(), AssignmentView.class);
    }

    String currentToken() {
        return transport.currentToken();
    }

    List<String> forbidden() {
        return transport.forbidden();
    }

    static String form(String clientId, String secret) {
        return DeviceTransport.form(clientId, secret);
    }

    private HttpResponse<String> exchange(java.util.function.Function<String, java.net.http.HttpRequest> request) {
        HttpResponse<String> response = transport.exchange(request);
        if (response.statusCode() / 100 != 2) {
            throw new DeviceRejected(response.statusCode());
        }
        return response;
    }
}
