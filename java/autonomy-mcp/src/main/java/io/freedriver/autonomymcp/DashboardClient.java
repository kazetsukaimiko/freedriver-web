package io.freedriver.autonomymcp;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Calls dashboard over the compose network. Never copies the Grok Authorization
 * header onto {@code /api/appliances} or any other portal route.
 */
@ApplicationScoped
public class DashboardClient {

    public static final String SUBJECT_HEADER = "X-Freedriver-Subject";
    public static final String ROLES_HEADER = "X-Freedriver-Roles";
    public static final String SECRET_HEADER = "X-Freedriver-Internal";

    private final String baseUrl;
    private final String secret;
    private final HttpClient http;
    private final CopyOnWriteArrayList<String> sentAuthorization = new CopyOnWriteArrayList<>();

    public DashboardClient(
            @ConfigProperty(name = "freedriver.mcp.dashboard-url") String baseUrl,
            @ConfigProperty(name = "freedriver.mcp.internal-secret") Optional<String> secret) {
        this.baseUrl = trimSlash(baseUrl);
        this.secret = secret.map(String::trim).filter(value -> !value.isEmpty()).orElse("");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public Call get(String path, String subject, Set<String> roles) {
        return send("GET", path, null, subject, roles);
    }

    public Call post(String path, String json, String subject, Set<String> roles) {
        return send("POST", path, json, subject, roles);
    }

    public boolean sentAuthorization() {
        return !sentAuthorization.isEmpty();
    }

    private Call send(String method, String path, String json, String subject, Set<String> roles) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header(SUBJECT_HEADER, subject)
                .header(ROLES_HEADER, String.join(",", roles))
                .header(SECRET_HEADER, secret);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json));
        } else {
            builder.GET();
        }
        HttpRequest request = builder.build();
        request.headers().firstValue("Authorization").ifPresent(sentAuthorization::add);
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Call(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Call(503, "");
        } catch (IOException e) {
            return new Call(503, "");
        }
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://dashboard:8080";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record Call(int status, String body) {
    }
}
