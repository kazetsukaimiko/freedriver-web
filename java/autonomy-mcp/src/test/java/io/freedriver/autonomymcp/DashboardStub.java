package io.freedriver.autonomymcp;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DashboardStub implements QuarkusTestResourceLifecycleManager {

    public DashboardStub() {
    }

    static final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    /** Each token-exchange form Keycloak would receive, field to values. */
    static final List<Map<String, List<String>>> exchanges = new CopyOnWriteArrayList<>();
    static final String TOKEN_PATH = "/realms/test/protocol/openid-connect/token";
    static final AtomicBoolean down = new AtomicBoolean(false);
    /** Keycloak refuses the exchange with 400 invalid_request. */
    static final AtomicBoolean refuseExchange = new AtomicBoolean(false);

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext(TOKEN_PATH, exchange -> {
            Map<String, List<String>> form = form(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchanges.add(form);
            if (refuseExchange.get()) {
                byte[] refused = "{\"error\":\"invalid_request\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, refused.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(refused);
                }
                return;
            }
            List<String> subject = form.getOrDefault("subject_token", List.of());
            String token = subject.size() == 1 ? exchanged(subject.getFirst()) : "none";
            byte[] bytes = ("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":300,\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/", exchange -> {
            if (down.get()) {
                exchange.close();
                return;
            }
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recorded.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    requestBody));
            String path = exchange.getRequestURI().getPath();
            String body;
            int status = 200;
            if ("/api/mcp/appliances".equals(path)) {
                body = "{\"instances\":[{\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"instanceName\":\"home\",\"lastUpdated\":\"2026-11-01T00:00:00Z\","
                        + "\"stale\":false,\"timeout\":false,\"appliances\":[{\"applianceName\":\"fridge\",\"on\":true}]}]}";
            } else if (path.startsWith("/api/appliances/")) {
                body = "{\"instanceId\":\"11111111-1111-1111-1111-111111111111\",\"instanceName\":\"home\","
                        + "\"lastUpdated\":\"2026-11-01T00:00:00Z\",\"stale\":false,\"timeout\":false,"
                        + "\"appliances\":[{\"applianceName\":\"fridge\",\"on\":false}]}";
            } else if ("/api/mcp/home-status".equals(path)) {
                body = "{\"brokerConnected\":true,\"lastUpdated\":\"2026-11-01T00:00:00Z\",\"stale\":false}";
            } else if ("/api/mcp/debug".equals(path)) {
                body = "{\"health\":\"UP\",\"errorCategory\":\"none\"}";
            } else if ("/api/mcp/scheduled-switches".equals(path)) {
                status = 202;
                body = "{}";
            } else {
                status = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return Map.of(
                "freedriver.mcp.dashboard-url", base,
                "quarkus.oidc-client.dashboard-exchange.auth-server-url", base + "/realms/test");
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** The token the stub Keycloak returns for this subject token. */
    static String exchanged(String subjectToken) {
        return "exchanged-" + subjectToken;
    }

    private static Map<String, List<String>> form(String body) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            fields.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return fields;
    }

    record Recorded(String method, String path, String authorization, String body) {
    }
}
