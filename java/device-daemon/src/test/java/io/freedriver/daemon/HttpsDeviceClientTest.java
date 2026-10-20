package io.freedriver.daemon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpsDeviceClientTest {

    private static final String SECRET = "test-device-secret";
    private static final String TOKEN = "test-access-token";

    @Test
    void device_routes_stay_on_the_fixed_https_origin() throws Exception {
        RoutingSender sender = new RoutingSender();
        HttpsDeviceClient client = client(sender);
        UUID id = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        client.register("luna", List.of("yuni"));
        client.heartbeat();
        assertTrue(client.poll().isEmpty());
        assertEquals("say hello", client.assignment(id).prompt());
        client.claim(id);
        client.update(id, new io.freedriver.dispatcher.model.UpdateAssignmentRequest(
                io.freedriver.dispatcher.model.AssignmentState.FAILED,
                null,
                "saw " + SECRET + " and " + TOKEN));

        assertEquals(1, sender.requests.stream().filter(request -> request.uri().equals(HttpsDeviceClient.TOKEN_ENDPOINT)).count());
        List<String> bodies = new ArrayList<>();
        for (HttpRequest request : sender.requests) {
            bodies.add(body(request));
        }
        HttpRequest tokenRequest = sender.requests.get(0);
        assertTrue(tokenRequest.headers().firstValue("Authorization").isEmpty());
        String form = bodies.get(0);
        assertTrue(form.contains("grant_type=client_credentials"));
        assertTrue(form.contains("client_id=pc-luna"));
        assertTrue(form.contains("client_secret=" + SECRET));

        List<String> paths = sender.requests.stream()
                .map(HttpRequest::uri)
                .filter(uri -> "app.freedriver.io".equals(uri.getHost()))
                .map(URI::getPath)
                .toList();
        assertEquals(List.of(
                "/api/device/register",
                "/api/device/heartbeat",
                "/api/device/assignments",
                "/api/device/assignments/" + id,
                "/api/device/assignments/" + id + "/claim",
                "/api/device/assignments/" + id), paths);
        for (int i = 0; i < sender.requests.size(); i++) {
            HttpRequest request = sender.requests.get(i);
            assertEquals("https", request.uri().getScheme());
            assertTrue(request.uri().getRawQuery() == null);
            assertFalse(request.uri().getPath().startsWith("/api/dispatcher"));
            if ("app.freedriver.io".equals(request.uri().getHost())) {
                assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElseThrow());
                assertFalse(bodies.get(i).contains(SECRET));
            }
        }
        String update = bodies.get(bodies.size() - 1);
        assertTrue(update.contains("\"note\":\"saw [redacted] and [redacted]\""));
        assertFalse(update.contains(SECRET));
        assertFalse(update.contains(TOKEN));
    }

    @Test
    void failures_do_not_include_the_token_or_the_secret() {
        RoutingSender sender = new RoutingSender();
        sender.tokenStatus = 400;
        sender.tokenBody = "{\"error\":\"" + SECRET + "\"}";
        IllegalStateException token = assertThrows(IllegalStateException.class, () -> client(sender).register("luna", List.of("yuni")));
        assertEquals("token request failed", token.getMessage());
        assertFalse(token.getMessage().contains(SECRET));

        RoutingSender expired = new RoutingSender();
        expired.apiStatus = 401;
        expired.apiBody = SECRET + " " + TOKEN;
        DeviceRejected rejected = assertThrows(DeviceRejected.class, () -> client(expired).heartbeat());
        assertEquals("device api 401", rejected.getMessage());
        assertFalse(rejected.getMessage().contains(SECRET));
        assertFalse(rejected.getMessage().contains(TOKEN));
        assertEquals(2, expired.requests.stream().filter(request -> request.uri().equals(HttpsDeviceClient.TOKEN_ENDPOINT)).count());
    }

    @Test
    void origin_is_fixed_and_tls_redirects_stay_strict() throws Exception {
        assertEquals(HttpClient.Redirect.NEVER, HttpsDeviceClient.REDIRECTS);
        assertEquals(Duration.ofSeconds(60), HttpsDeviceClient.POLL_TIMEOUT);
        assertFalse(HttpsDeviceClient.POLL_TIMEOUT.equals(Duration.ofSeconds(75)));
        assertTrue(HttpsDeviceClient.POLL_TIMEOUT.compareTo(Duration.ofSeconds(25)) > 0);
        HttpsDeviceClient.requireFixed(URI.create("https://app.freedriver.io/api/device/register"));
        HttpsDeviceClient.requireFixed(HttpsDeviceClient.TOKEN_ENDPOINT);
        assertThrows(IllegalArgumentException.class, () -> HttpsDeviceClient.requireFixed(URI.create("http://app.freedriver.io/api/device/register")));
        assertThrows(IllegalArgumentException.class, () -> HttpsDeviceClient.requireFixed(URI.create("https://evil.example/api/device/register")));
        assertThrows(IllegalArgumentException.class, () -> HttpsDeviceClient.requireFixed(URI.create("https://app.freedriver.io/api/dispatcher/jobs")));
        assertThrows(IllegalArgumentException.class, () -> HttpsDeviceClient.requireFixed(URI.create("https://user:pass@app.freedriver.io/api/device/register")));
        assertThrows(IllegalArgumentException.class, () -> HttpsDeviceClient.requireFixed(URI.create("https://app.freedriver.io/api/device/register?access_token=x")));

        String source = Files.readString(Path.of("src/main/java/io/freedriver/daemon/HttpsDeviceClient.java"));
        assertTrue(source.contains("followRedirects(REDIRECTS)"));
        assertTrue(source.contains("Redirect.NEVER"));
        assertTrue(source.contains("path.startsWith(\"/api/dispatcher\")"));
        assertFalse(source.contains("/api/dispatcher/"));
        assertFalse(source.contains("TrustAll"));
        assertFalse(source.contains("InsecureTrustManager"));
        assertTrue(source.contains("https://app.freedriver.io"));
    }

    @Test
    void form_encodes_the_secret() {
        assertTrue(HttpsDeviceClient.form("pc.luna", "a+b/c=").contains("client_secret=a%2Bb%2Fc%3D"));
    }

    private static HttpsDeviceClient client(RoutingSender sender) {
        return new HttpsDeviceClient("pc-luna", SECRET, SECRET + "\n", sender, Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneId.of("UTC")));
    }

    private static String body(HttpRequest request) throws Exception {
        if (request.bodyPublisher().isEmpty()) {
            return "";
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                sink.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        assertTrue(done.await(1, TimeUnit.SECONDS));
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static final class RoutingSender implements HttpSender {
        final List<HttpRequest> requests = new ArrayList<>();
        int tokenStatus = 200;
        String tokenBody = "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":120}";
        int apiStatus = 200;
        String apiBody;

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            requests.add(request);
            if (request.uri().equals(HttpsDeviceClient.TOKEN_ENDPOINT)) {
                return new TextResponse(tokenStatus, tokenBody, request);
            }
            String path = request.uri().getPath();
            String body = apiBody;
            if (body == null && "/api/device/assignments".equals(path)) {
                body = "[]";
            } else if (body == null && path.startsWith("/api/device/assignments/")) {
                body = """
                        {"assignmentId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","jobId":"bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee","deviceId":"pc-luna","agentName":"yuni","channel":"TEXT","prompt":"say hello","state":"QUEUED","grokSessionId":null,"note":null,"extra":1}
                        """;
            } else if (body == null) {
                body = "{}";
            }
            return new TextResponse(apiStatus, body, request);
        }
    }

    private record TextResponse(int status, String text, HttpRequest request) implements HttpResponse<String> {
        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (left, right) -> true);
        }

        @Override
        public String body() {
            return text;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
