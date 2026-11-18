package io.freedriver.daemon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenBot's Local scripts API, read from the automation folder. The folder's {@code url} and
 * {@code token} change at each OpenBot start, so both are read again after a refused connection or
 * a 401. Its own JDK client follows no redirect and keeps TLS verification on; it carries only the
 * Local scripts token, never a Keycloak bearer or the client secret. A {@code url} is accepted only
 * when every address of its host is loopback.
 */
final class ScriptsAgentHost implements AgentHost {

    static final HttpClient.Redirect REDIRECTS = HttpClient.Redirect.NEVER;
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_FILE = 4096;

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private record Endpoint(URI base, String token) {
    }

    private final Path automationDir;
    private final HttpSender sender;
    private final Resolver resolver;
    private volatile Endpoint endpoint;
    private volatile String lastToken;

    static ScriptsAgentHost create(Path automationDir) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(REDIRECTS)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new ScriptsAgentHost(automationDir, new JdkHttpSender(client), InetAddress::getAllByName);
    }

    ScriptsAgentHost(Path automationDir, HttpSender sender, Resolver resolver) {
        this.automationDir = automationDir;
        this.sender = sender;
        this.resolver = resolver;
    }

    @Override
    public List<Agent> agents() {
        HttpResponse<String> response = call("GET", "/v1/agents", null);
        if (response.statusCode() / 100 != 2) {
            throw new Unavailable("agent list answered " + response.statusCode());
        }
        JsonNode node = parse(response.body());
        if (node == null || !node.isArray()) {
            throw new Unavailable("agent list was not usable");
        }
        List<Agent> agents = new ArrayList<>();
        for (JsonNode item : node) {
            String id = item.path("id").asText("");
            String name = item.path("name").asText("");
            if (!id.isEmpty() && !name.isEmpty()) {
                agents.add(new Agent(id, name));
            }
        }
        return List.copyOf(agents);
    }

    /**
     * Sends once. A refused connection or a missing file before anything was written is
     * {@link Unavailable}, so the caller may try again. A 401 reads the files again and sends the
     * same message once more. A timeout, a 5xx or a lost answer after the request was written is
     * {@link Lost}: the message is not sent again.
     */
    @Override
    public SendResult send(String agentId, String clientMessageId, String text) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("clientMessageId", clientMessageId);
        String json;
        try {
            json = DeviceTransport.JSON.writeValueAsString(body);
        } catch (JsonProcessingException failed) {
            return new Refused(0, "the message was not usable");
        }
        String path = "/v1/agents/" + segment(agentId) + "/messages";
        HttpResponse<String> response;
        try {
            response = sendOnce("POST", path, json, endpoint(false));
            if (response.statusCode() == 401) {
                response = sendOnce("POST", path, json, endpoint(true));
            }
        } catch (NotSent notSent) {
            endpoint = null;
            throw new Unavailable("OpenBot is not reachable");
        } catch (Written written) {
            return new Lost("the message call ended without a receipt");
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            JsonNode node = parse(response.body());
            List<String> ids = new ArrayList<>();
            if (node != null) {
                for (JsonNode delivery : node.path("deliveries")) {
                    ids.add(delivery.path("id").asText(""));
                }
            }
            return new Sent(ids);
        }
        if (status == 401) {
            endpoint = null;
            return new Lost("OpenBot refused the token twice");
        }
        if (status / 100 == 4) {
            return new Refused(status, errorText(response.body(), status));
        }
        return new Lost("OpenBot answered " + status);
    }

    @Override
    public Delivery delivery(String agentId, String deliveryId) {
        HttpResponse<String> response = call(
                "GET", "/v1/agents/" + segment(agentId) + "/deliveries/" + segment(deliveryId), null);
        if (response.statusCode() == 404) {
            return Delivery.missing();
        }
        if (response.statusCode() / 100 != 2) {
            throw new Unavailable("delivery read answered " + response.statusCode());
        }
        JsonNode node = parse(response.body());
        String status = node == null ? "" : node.path("status").asText("");
        return Delivery.of(status);
    }

    @Override
    public List<String> forbidden() {
        String token = lastToken;
        return token == null ? List.of() : List.of(token);
    }

    /** A read: a refused connection or a 401 reads the files again and repeats it once. */
    private HttpResponse<String> call(String method, String path, String json) {
        try {
            HttpResponse<String> response;
            try {
                response = sendOnce(method, path, json, endpoint(false));
            } catch (NotSent refused) {
                endpoint = null;
                response = sendOnce(method, path, json, endpoint(true));
            }
            if (response.statusCode() == 401) {
                response = sendOnce(method, path, json, endpoint(true));
                if (response.statusCode() == 401) {
                    endpoint = null;
                    throw new Unavailable("OpenBot refused the token");
                }
            }
            if (response.statusCode() / 100 == 5) {
                throw new Unavailable("OpenBot answered " + response.statusCode());
            }
            return response;
        } catch (NotSent | Written failed) {
            endpoint = null;
            throw new Unavailable("OpenBot is not reachable");
        }
    }

    private HttpResponse<String> sendOnce(String method, String path, String json, Endpoint target) {
        URI uri = URI.create(target.base().getScheme() + "://" + target.base().getRawAuthority() + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(CALL_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + target.token());
        if (json == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }
        try {
            return sender.send(builder.build());
        } catch (ConnectException | HttpConnectTimeoutException refused) {
            throw new NotSent();
        } catch (IOException failed) {
            throw new Written();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Written();
        }
    }

    private Endpoint endpoint(boolean reload) {
        Endpoint current = endpoint;
        if (current != null && !reload) {
            return current;
        }
        String url = readFile("url");
        String token = readFile("token");
        if (token.isEmpty() || token.chars().anyMatch(c -> c < 0x21 || c > 0x7E)) {
            throw new Unavailable("OpenBot token is not usable");
        }
        URI base = loopback(url, resolver);
        current = new Endpoint(base, token);
        lastToken = token;
        endpoint = current;
        return current;
    }

    /** Accepts http or https with no user-info, query or fragment, on a host that is only loopback. */
    static URI loopback(String url, Resolver resolver) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception bad) {
            throw new Unavailable("OpenBot url is not usable");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new Unavailable("OpenBot url is not http");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new Unavailable("OpenBot url is not usable");
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty() && !"/".equals(rawPath)) {
            throw new Unavailable("OpenBot url is not usable");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new Unavailable("OpenBot url is not usable");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        } catch (UnknownHostException unknown) {
            throw new Unavailable("OpenBot url is not usable");
        }
        if (addresses == null || addresses.length == 0) {
            throw new Unavailable("OpenBot url is not usable");
        }
        for (InetAddress address : addresses) {
            if (!address.isLoopbackAddress()) {
                throw new Unavailable("OpenBot url is not loopback");
            }
        }
        return uri;
    }

    private String readFile(String name) {
        Path file = automationDir.resolve(name);
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new Unavailable("OpenBot is not running");
            }
            if (Files.size(file) > MAX_FILE) {
                throw new Unavailable("OpenBot file is too large");
            }
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException failed) {
            throw new Unavailable("OpenBot is not running");
        }
    }

    /** Percent-encodes one path segment: only unreserved characters stay as they are. */
    static String segment(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static JsonNode parse(String body) {
        try {
            return body == null ? null : DeviceTransport.JSON.readTree(body);
        } catch (IOException failed) {
            return null;
        }
    }

    private static String errorText(String body, int status) {
        JsonNode node = parse(body);
        if (node != null && node.path("error").isTextual() && !node.path("error").asText().isBlank()) {
            return node.path("error").asText();
        }
        return "HTTP " + status;
    }

    /** Nothing was written: the connection was refused or did not open. */
    private static final class NotSent extends RuntimeException {
        NotSent() {
            super(null, null, false, false);
        }
    }

    /** The request may have been written and no answer came back. */
    private static final class Written extends RuntimeException {
        Written() {
            super(null, null, false, false);
        }
    }
}
