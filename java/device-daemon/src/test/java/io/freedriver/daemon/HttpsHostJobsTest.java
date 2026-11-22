package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentState;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostPaths;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.ReportedBot;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpsHostJobsTest {

    private static final String SECRET = "test-device-secret";
    private static final String TOKEN = "test-access-token";
    private static final String SCRIPTS_TOKEN = "openbot-scripts-token";
    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void each_call_has_its_method_path_and_body() throws Exception {
        Sender sender = new Sender();
        HttpsHostJobs host = host(sender);

        host.report(new HostReport("luna", List.of(new ReportedBot("Tech Ops", "idle", ""))));
        assertTrue(host.take(new TakeRequest(50, List.of(ID))).isEmpty());
        assertEquals(AssignmentState.RUNNING, host.start(ID, new StartRequest("delivery-1")).orElseThrow().state());
        host.finish(ID, new FinishRequest(false, "saw " + SECRET + " and " + TOKEN + " and " + SCRIPTS_TOKEN));

        List<HttpRequest> api = sender.requests.stream()
                .filter(request -> "app.freedriver.io".equals(request.uri().getHost()))
                .toList();
        assertEquals(List.of(
                "/api/dispatcher/host/report",
                "/api/dispatcher/host/take",
                "/api/dispatcher/host/jobs/" + ID + "/start",
                "/api/dispatcher/host/jobs/" + ID + "/finish"), api.stream().map(r -> r.uri().getPath()).toList());
        for (HttpRequest request : api) {
            assertEquals("POST", request.method());
            assertEquals("https", request.uri().getScheme());
            assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElseThrow());
        }
        assertEquals("{\"hostname\":\"luna\",\"bots\":[{\"name\":\"Tech Ops\",\"state\":\"idle\",\"activity\":\"\"}]}",
                TestHttp.body(api.get(0)));
        assertEquals("{\"waitSeconds\":50,\"holding\":[\"" + ID + "\"]}", TestHttp.body(api.get(1)));
        assertEquals(Duration.ofSeconds(60), api.get(1).timeout().orElseThrow());
        assertEquals("{\"deliveryRef\":\"delivery-1\"}", TestHttp.body(api.get(2)));
        String finish = TestHttp.body(api.get(3));
        assertEquals("{\"succeeded\":false,\"note\":\"saw [redacted] and [redacted] and [redacted]\"}", finish);
    }

    @Test
    void report_reads_the_view_device_answer() throws Exception {
        Sender sender = new Sender();
        sender.apiBody = "{\"deviceId\":\"freedriver-openbot\",\"hostname\":\"luna\","
                + "\"agents\":[\"PM\",\"TechOps\",\"Architect\"],"
                + "\"lastHeartbeat\":\"2026-11-22T11:38:00Z\","
                + "\"activity\":{\"PM\":\"idle\"},\"rosterNote\":\"ignored\"}";
        DeviceView view = host(sender).report(new HostReport("luna", List.of(new ReportedBot("PM", "idle", ""))));

        assertEquals(Instant.parse("2026-11-22T11:38:00Z"), view.lastHeartbeat());
        assertEquals(Map.of("PM", "idle"), view.activity());
        assertEquals("freedriver-openbot", view.deviceId());
        assertEquals(List.of("PM", "TechOps", "Architect"), view.agents());
        assertEquals("\"2026-11-22T11:38:00Z\"",
                DeviceTransport.JSON.writeValueAsString(Instant.parse("2026-11-22T11:38:00Z")));
    }

    @Test
    void take_timeout_is_the_wait_plus_ten_seconds() {
        Sender sender = new Sender();
        host(sender).take(new TakeRequest(5, List.of()));
        HttpRequest take = sender.requests.get(sender.requests.size() - 1);
        assertEquals(Duration.ofSeconds(15), take.timeout().orElseThrow());
    }

    @Test
    void unauthorized_fetches_a_new_token_and_repeats_once() {
        Sender sender = new Sender();
        sender.unauthorizedOnce = true;
        host(sender).report(new HostReport("luna", List.of(new ReportedBot("yuni", "idle", ""))));

        assertEquals(2, sender.requests.stream().filter(r -> r.uri().equals(DeviceTransport.TOKEN_ENDPOINT)).count());
        assertEquals(2, sender.requests.stream().filter(r -> r.uri().getPath().endsWith("/report")).count());
    }

    @Test
    void a_refusal_is_device_rejected_with_its_status() {
        Sender sender = new Sender();
        sender.apiStatus = 400;
        HttpsHostJobs host = host(sender);
        assertEquals(400, assertThrows(DeviceRejected.class,
                () -> host.report(new HostReport("luna", List.of(new ReportedBot("yuni", "idle", ""))))).status);
        assertEquals(400, assertThrows(DeviceRejected.class, () -> host.take(new TakeRequest(0, List.of()))).status);
        assertEquals(400, assertThrows(DeviceRejected.class, () -> host.start(ID, new StartRequest("d"))).status);
        assertEquals(400, assertThrows(DeviceRejected.class, () -> host.finish(ID, new FinishRequest(false, "x"))).status);

        Sender busy = new Sender();
        busy.apiStatus = 503;
        assertEquals(503, assertThrows(DeviceRejected.class, () -> host(busy).take(new TakeRequest(0, List.of()))).status);
    }

    @Test
    void conflict_from_start_or_finish_is_empty_and_not_parsed() {
        Sender sender = new Sender();
        sender.apiStatus = 409;
        sender.apiBody = "not json";
        HttpsHostJobs host = host(sender);
        assertTrue(host.start(ID, new StartRequest("delivery-1")).isEmpty());
        assertTrue(host.finish(ID, new FinishRequest(false, "x")).isEmpty());
    }

    @Test
    void only_the_four_host_paths_and_the_token_endpoint_are_sent() {
        DeviceTransport.requireFixed(URI.create("https://app.freedriver.io/api/dispatcher/host/report"), HttpsHostJobs::allowed);
        DeviceTransport.requireFixed(URI.create("https://app.freedriver.io/api/dispatcher/host/take"), HttpsHostJobs::allowed);
        DeviceTransport.requireFixed(URI.create("https://app.freedriver.io" + HttpsHostJobs.path(HostPaths.START, ID)), HttpsHostJobs::allowed);
        DeviceTransport.requireFixed(URI.create("https://app.freedriver.io" + HttpsHostJobs.path(HostPaths.FINISH, ID)), HttpsHostJobs::allowed);
        DeviceTransport.requireFixed(DeviceTransport.TOKEN_ENDPOINT, HttpsHostJobs::allowed);
        for (String refused : List.of(
                "https://app.freedriver.io/api/device/register",
                "https://app.freedriver.io/api/dispatcher/jobs",
                "https://app.freedriver.io/api/dispatcher/host/",
                "https://app.freedriver.io/api/dispatcher/host/jobs/not-a-uuid/start",
                "https://app.freedriver.io/api/dispatcher/host/report/extra",
                "https://app.freedriver.io/api/dispatcher/host/jobs/" + ID + "/../../report",
                "https://app.freedriver.io/api/dispatcher/host/./report",
                "https://app.freedriver.io/api/dispatcher/host/%2e%2e/report",
                "https://app.freedriver.io/api/dispatcher/host%2Freport",
                "https://app.freedriver.io/api/dispatcher/host/%252e%252e/report",
                "http://app.freedriver.io/api/dispatcher/host/report",
                "https://app.freedriver.io:8443/api/dispatcher/host/report",
                "https://evil.example/api/dispatcher/host/report",
                "https://app.freedriver.io/api/dispatcher/host/report?x=1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DeviceTransport.requireFixed(URI.create(refused), HttpsHostJobs::allowed), refused);
        }
    }

    @Test
    void a_path_outside_the_list_is_refused_before_it_is_sent() {
        Sender sender = new Sender();
        HttpsHostJobs host = host(sender);
        DeviceTransport transport = new DeviceTransport("freedriver-openbot", SECRET, SECRET, sender,
                Clock.systemUTC(), HttpsHostJobs::allowed);
        assertThrows(IllegalArgumentException.class,
                () -> transport.exchange(token -> transport.noBody("GET", "/api/device/assignments", token, Duration.ofSeconds(1))));
        assertThrows(IllegalArgumentException.class,
                () -> transport.exchange(token -> transport.noBody("POST", "/api/dispatcher/host/../jobs", token, Duration.ofSeconds(1))));
        assertTrue(sender.requests.stream().noneMatch(r -> "app.freedriver.io".equals(r.uri().getHost())));
        assertTrue(host.forbidden().contains(SECRET));
    }

    @Test
    void no_host_path_is_typed_a_second_time() throws Exception {
        String source = Files.readString(Path.of("src/main/java/io/freedriver/daemon/HttpsHostJobs.java"));
        assertFalse(source.contains("\"/api/"));
        assertFalse(source.contains("/report\""));
        assertFalse(source.contains("/take\""));
        assertFalse(source.contains("/start\""));
        assertFalse(source.contains("/finish\""));
        String transport = Files.readString(Path.of("src/main/java/io/freedriver/daemon/DeviceTransport.java"));
        assertFalse(transport.contains("/api/"));
    }

    private static HttpsHostJobs host(Sender sender) {
        return new HttpsHostJobs("freedriver-openbot", SECRET, SECRET + "\n", sender,
                Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneId.of("UTC")),
                () -> List.of(SCRIPTS_TOKEN));
    }

    private static final class Sender implements HttpSender {
        final List<HttpRequest> requests = new ArrayList<>();
        int apiStatus = 200;
        String apiBody;
        boolean unauthorizedOnce;

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            requests.add(request);
            if (request.uri().equals(DeviceTransport.TOKEN_ENDPOINT)) {
                return TestHttp.response(200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":120}", request);
            }
            if (unauthorizedOnce) {
                unauthorizedOnce = false;
                return TestHttp.response(401, "", request);
            }
            String path = request.uri().getPath();
            String body = apiBody;
            if (body == null) {
                if (path.endsWith("/take")) {
                    body = "[]";
                } else if (path.endsWith("/report")) {
                    body = "{\"deviceId\":\"freedriver-openbot\",\"hostname\":\"luna\",\"agents\":[\"Tech Ops\"]}";
                } else {
                    body = "{\"assignmentId\":\"" + ID + "\",\"state\":\"RUNNING\",\"deliveryRef\":\"delivery-1\"}";
                }
            }
            return TestHttp.response(apiStatus, body, request);
        }
    }
}
