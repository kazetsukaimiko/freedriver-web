package io.freedriver.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector against a local stack: the real {@link HttpsHostJobs} over the {@link HttpSender}
 * seam for the dispatcher, and the real {@link ScriptsAgentHost} against a stub of OpenBot's Local
 * scripts API bound to 127.0.0.1. No connection leaves this machine.
 */
class ConnectorLocalStackTest {

    private static final String SCRIPTS_TOKEN = "local-scripts-token-0123";
    private static final UUID ASSIGNMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path automation;

    private HttpServer openBot;
    private OpenBotWorker connector;

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> deliveryReads = new ConcurrentHashMap<>();
    private final List<String> dispatcherCalls = new CopyOnWriteArrayList<>();
    private final List<String> dispatcherBodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (connector != null) {
            connector.close();
        }
        if (openBot != null) {
            openBot.stop(0);
        }
    }

    @Test
    void one_job_is_handed_to_the_local_scripts_stub_and_ends() throws Exception {
        openBot = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        openBot.createContext("/v1/agents", this::openBot);
        openBot.start();
        assertEquals("127.0.0.1", openBot.getAddress().getAddress().getHostAddress());
        Files.writeString(automation.resolve("url"), "http://127.0.0.1:" + openBot.getAddress().getPort() + "\n");
        Files.writeString(automation.resolve("token"), SCRIPTS_TOKEN + "\n");

        ScriptsAgentHost agents = ScriptsAgentHost.create(automation);
        HttpsHostJobs host = new HttpsHostJobs(
                OpenBotWorker.DEVICE_ID, "client-secret-value", "client-secret-value\n",
                new Dispatcher(), Clock.systemUTC(), agents::forbidden);
        connector = OpenBotWorker.create(
                host, agents, DaemonSettings.openBot("luna", List.of("Tech Ops"), automation), host::forbidden);

        connector.start();

        waitFor(() -> dispatcherCalls.stream().anyMatch(call -> call.endsWith("/finish")));
        assertEquals(1, messages.size());
        JsonNode message = DeviceTransport.JSON.readTree(messages.get(0));
        assertEquals(ASSIGNMENT.toString(), message.path("clientMessageId").asText());
        assertTrue(message.path("text").asText().endsWith(OpenBotWorker.DELIMITER + "\nsay hello"));
        assertTrue(dispatcherCalls.contains("POST /api/dispatcher/host/report"));
        assertTrue(dispatcherCalls.contains("POST /api/dispatcher/host/jobs/" + ASSIGNMENT + "/start"));
        assertTrue(dispatcherBodies.contains("{\"deliveryRef\":\"delivery-1\"}"));
        assertTrue(dispatcherBodies.contains("{\"hostname\":\"luna\",\"bots\":[{\"name\":\"tech ops\",\"state\":\"idle\",\"activity\":\"\"}]}")
                || dispatcherBodies.contains("{\"hostname\":\"luna\",\"bots\":[{\"name\":\"tech ops\",\"state\":\"busy\",\"activity\":\"\"}]}"));
        assertTrue(dispatcherBodies.contains("{\"succeeded\":false,\"note\":\"the turn ended without a result (completed)\"}"));
        waitFor(() -> connector.held().isEmpty());
    }

    private void openBot(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + SCRIPTS_TOKEN).equals(auth) || exchange.getRequestHeaders().containsKey("Origin")) {
            reply(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/v1/agents".equals(path)) {
            reply(exchange, 200, "[{\"id\":\"agent-1\",\"name\":\"tech ops\",\"routines\":[]}]");
        } else if ("POST".equals(method) && "/v1/agents/agent-1/messages".equals(path)) {
            messages.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 202, "{\"messageId\":\"m-1\",\"deliveries\":[{\"id\":\"delivery-1\",\"recipientAgentId\":\"agent-1\",\"status\":\"queued\",\"position\":0}]}");
        } else if ("GET".equals(method) && "/v1/agents/agent-1/deliveries/delivery-1".equals(path)) {
            int read = deliveryReads.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            String status = read == 1 ? "running" : "completed";
            reply(exchange, 200, "{\"id\":\"delivery-1\",\"status\":\"" + status + "\",\"turnId\":\"t-1\",\"error\":null,\"answer\":null}");
        } else {
            reply(exchange, 404, "{\"error\":\"not found\"}");
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** The dispatcher, behind the seam: nothing is sent to app.freedriver.io or auth.freedriver.io. */
    private final class Dispatcher implements HttpSender {
        private final AtomicBoolean handedOut = new AtomicBoolean();

        @Override
        public HttpResponse<String> send(HttpRequest request) throws InterruptedException {
            if (request.uri().equals(DeviceTransport.TOKEN_ENDPOINT)) {
                return TestHttp.response(200, "{\"access_token\":\"access-token-value\",\"expires_in\":300}", request);
            }
            String path = request.uri().getPath();
            dispatcherCalls.add(request.method() + " " + path);
            try {
                dispatcherBodies.add(TestHttp.body(request));
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
            if (path.endsWith("/report")) {
                return TestHttp.response(200, "{\"deviceId\":\"freedriver-openbot\",\"hostname\":\"luna\"}", request);
            }
            if (path.endsWith("/take")) {
                if (handedOut.compareAndSet(false, true)) {
                    return TestHttp.response(200, "[{\"assignmentId\":\"" + ASSIGNMENT + "\",\"agentName\":\"Tech Ops\","
                            + "\"channel\":\"TEXT\",\"prompt\":\"say hello\",\"state\":\"CLAIMED\"}]", request);
                }
                TimeUnit.MILLISECONDS.sleep(100);
                return TestHttp.response(200, "[]", request);
            }
            if (path.endsWith("/start")) {
                return TestHttp.response(200, "{\"assignmentId\":\"" + ASSIGNMENT + "\",\"state\":\"RUNNING\","
                        + "\"deliveryRef\":\"delivery-1\"}", request);
            }
            return TestHttp.response(200, "{\"assignmentId\":\"" + ASSIGNMENT + "\",\"state\":\"FAILED\"}", request);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met");
            }
            Thread.sleep(20);
        }
    }
}
