package io.freedriver.autonomymcp;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DashboardStub implements QuarkusTestResourceLifecycleManager {

    public DashboardStub() {
    }

    static final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    static final AtomicBoolean down = new AtomicBoolean(false);

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            if (down.get()) {
                exchange.close();
                return;
            }
            recorded.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst(DashboardClient.SUBJECT_HEADER)));
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
        return Map.of(
                "freedriver.mcp.dashboard-url",
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    record Recorded(String method, String path, String authorization, String subject) {
    }
}
