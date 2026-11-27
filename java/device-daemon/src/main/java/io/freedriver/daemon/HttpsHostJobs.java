package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostPaths;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The host client. It sends only to the four {@link HostPaths} routes under
 * {@code https://app.freedriver.io/api/dispatcher/host/} and to the fixed token endpoint.
 */
final class HttpsHostJobs implements HostJobs {

    static final Duration TAKE_MARGIN = Duration.ofSeconds(10);

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String ID_PARAMETER = "{" + HostPaths.ASSIGNMENT_ID + "}";
    private static final Pattern ALLOWED = Pattern.compile(
            Pattern.quote(HostPaths.ROOT + HostPaths.REPORT)
                    + "|" + Pattern.quote(HostPaths.ROOT + HostPaths.TAKE)
                    + "|" + withId(HostPaths.START)
                    + "|" + withId(HostPaths.FINISH));

    private final DeviceTransport transport;
    private final Supplier<List<String>> alsoForbidden;

    static HttpsHostJobs create(String clientId, SecretFile.Material material, Supplier<List<String>> alsoForbidden) {
        return new HttpsHostJobs(
                clientId,
                material.secret(),
                material.raw(),
                new JdkHttpSender(DeviceTransport.newVerifyingClient()),
                Clock.systemUTC(),
                alsoForbidden);
    }

    HttpsHostJobs(
            String clientId,
            String secret,
            String rawSecret,
            HttpSender sender,
            Clock clock,
            Supplier<List<String>> alsoForbidden) {
        this.transport = new DeviceTransport(clientId, secret, rawSecret, sender, clock, HttpsHostJobs::allowed);
        this.alsoForbidden = alsoForbidden == null ? List::of : alsoForbidden;
    }

    static boolean allowed(String rawPath) {
        return rawPath != null && ALLOWED.matcher(rawPath).matches();
    }

    @Override
    public DeviceView report(HostReport report) {
        HttpResponse<String> response = transport.exchange(token -> transport.json(
                "POST", HostPaths.ROOT + HostPaths.REPORT, token, report, DeviceTransport.CALL_TIMEOUT));
        requireSuccess(response);
        return DeviceTransport.read(response.body(), DeviceView.class);
    }

    @Override
    public List<AssignmentView> take(TakeRequest request) {
        Duration timeout = Duration.ofSeconds(request.waitSeconds()).plus(TAKE_MARGIN);
        HttpResponse<String> response = transport.exchange(token -> transport.json(
                "POST", HostPaths.ROOT + HostPaths.TAKE, token, request, timeout));
        requireSuccess(response);
        List<AssignmentView> views = DeviceTransport.readList(response.body(), AssignmentView.class);
        return views == null ? List.of() : views;
    }

    @Override
    public Optional<AssignmentView> start(UUID assignmentId, StartRequest request) {
        HttpResponse<String> response = transport.exchange(token -> transport.json(
                "POST", path(HostPaths.START, assignmentId), token, request, DeviceTransport.CALL_TIMEOUT));
        if (response.statusCode() == 409) {
            return Optional.empty();
        }
        requireSuccess(response);
        return Optional.ofNullable(DeviceTransport.read(response.body(), AssignmentView.class));
    }

    @Override
    public Optional<AssignmentView> finish(UUID assignmentId, FinishRequest request) {
        HttpResponse<String> response = transport.exchange(token -> {
            List<String> forbidden = new ArrayList<>(transport.forbidden());
            forbidden.add(token);
            forbidden.addAll(alsoForbidden.get());
            FinishRequest safe = new FinishRequest(
                    request.succeeded(),
                    Notes.forUpdate(request.note(), forbidden.toArray(String[]::new)));
            return transport.json(
                    "POST", path(HostPaths.FINISH, assignmentId), token, safe, DeviceTransport.CALL_TIMEOUT);
        });
        if (response.statusCode() == 409) {
            return Optional.empty();
        }
        requireSuccess(response);
        return Optional.ofNullable(DeviceTransport.read(response.body(), AssignmentView.class));
    }

    List<String> forbidden() {
        return transport.forbidden();
    }

    static String path(String template, UUID assignmentId) {
        return HostPaths.ROOT + template.replace(ID_PARAMETER, assignmentId.toString());
    }

    private static String withId(String template) {
        int at = template.indexOf(ID_PARAMETER);
        return Pattern.quote(HostPaths.ROOT + template.substring(0, at))
                + UUID_PATTERN
                + Pattern.quote(template.substring(at + ID_PARAMETER.length()));
    }

    private static void requireSuccess(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new DeviceRejected(response.statusCode());
        }
    }
}
