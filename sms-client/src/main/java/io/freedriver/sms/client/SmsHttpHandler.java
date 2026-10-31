package io.freedriver.sms.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP invocation for {@link SmsClient}. Does not log the request, the response, headers, or the
 * shared secret. Redirects are disabled on the client.
 */
final class SmsHttpHandler implements InvocationHandler {

    private static final int MAX_BODY = 8192;
    private static final ObjectMapper JSON = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(SmsClientConfig.CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final SmsClientConfig config;
    private final Duration timeout;

    SmsHttpHandler(SmsClientConfig config, Duration timeout) {
        this.config = config;
        this.timeout = timeout;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return objectCall(proxy, method, args);
        }
        if (args == null || args.length != 1 || args[0] == null) {
            throw new SmsClientException(0, "bad-request", null);
        }
        URI uri = endpoint(method);
        String secret = usable(config.sharedSecret());
        if (secret == null) {
            throw new SmsClientException(401, "unauthorized", null);
        }
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(args[0]);
        } catch (IOException ex) {
            throw new SmsClientException(0, "bad-request", null);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(SmsClientConfig.HEADER, secret);
        String correlationId = SmsCallContext.correlationId();
        if (correlationId != null) {
            builder.header(SmsClientConfig.CORRELATION_HEADER, correlationId);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new SmsClientException(0, "unavailable", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SmsClientException(0, "unavailable", null);
        }
        byte[] responseBody = response.body() == null ? new byte[0] : response.body();
        int status = response.statusCode();
        if (responseBody.length > MAX_BODY) {
            throw new SmsClientException(status, null, null);
        }
        if (status == 200) {
            try {
                return JSON.readValue(responseBody, method.getReturnType());
            } catch (IOException ex) {
                throw new SmsClientException(status, null, null);
            }
        }
        Long retry = status == 429 ? retryAfter(response) : null;
        throw new SmsClientException(status, errorToken(responseBody), retry, reasonToken(responseBody));
    }

    private URI endpoint(Method method) {
        Method declared = contractMethod(method);
        if (declared.getAnnotation(POST.class) == null) {
            throw new SmsClientException(0, "bad-request", null);
        }
        String path = join(pathValue(SmsApiPaths.classPath(declared)), pathValue(declared.getAnnotation(Path.class)));
        if (!"/sms/send".equals(path) && !"/sms/verify".equals(path)) {
            throw new SmsClientException(0, "unavailable", null);
        }
        URI base = config.baseUrl();
        if (!allowedBase(base)) {
            throw new SmsClientException(0, "unavailable", null);
        }
        URI uri = base.resolve(path);
        if (!allowed(uri) || !path.equals(uri.getPath())) {
            throw new SmsClientException(0, "unavailable", null);
        }
        return uri;
    }

    private static Method contractMethod(Method method) {
        if (method.getAnnotation(POST.class) != null || method.getDeclaringClass().getAnnotation(Path.class) != null) {
            return method;
        }
        Class<?> type = method.getDeclaringClass();
        for (Class<?> iface : type.getInterfaces()) {
            try {
                return iface.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                // not this interface
            }
        }
        return method;
    }

    private static String pathValue(Path path) {
        return path == null ? "" : path.value();
    }

    private static String join(String classPath, String methodPath) {
        String left = classPath == null ? "" : classPath;
        String right = methodPath == null ? "" : methodPath;
        String path;
        if (left.endsWith("/") && right.startsWith("/")) {
            path = left + right.substring(1);
        } else if (!left.isEmpty() && !right.isEmpty() && !left.endsWith("/") && !right.startsWith("/")) {
            path = left + "/" + right;
        } else {
            path = left + right;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    /** Production host is {@code sms:8080}. Loopback with any other port is the test server. */
    static boolean allowed(URI uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        String host = uri.getHost();
        int port = uri.getPort();
        if ("sms".equals(host) && port == 8080) {
            return true;
        }
        return "127.0.0.1".equals(host) && port > 0 && port != 8080;
    }

    static boolean allowedBase(URI base) {
        if (!allowed(base)) {
            return false;
        }
        String path = base.getPath();
        return path == null || path.isEmpty() || "/".equals(path);
    }

    /** Null when the secret must not be sent. Never returns a value that can break a header. */
    static String usable(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || SmsClientConfig.PLACEHOLDER.equals(trimmed) || trimmed.length() > 256) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c <= ' ' || c == 0x7f) {
                return null;
            }
        }
        return trimmed;
    }

    private static String errorToken(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode error = root.get("error");
            if (error == null || !error.isTextual()) {
                return null;
            }
            return error.textValue();
        } catch (IOException ex) {
            return null;
        }
    }

    private static String reasonToken(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode reason = root.get("reason");
            if (reason == null || !reason.isTextual()) {
                return null;
            }
            return reason.textValue();
        } catch (IOException ex) {
            return null;
        }
    }

    private static Long retryAfter(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return null;
        }
        String trimmed = header.get().trim();
        if (trimmed.isEmpty() || trimmed.length() > 10) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (value < 0 || value > 86400) {
            return null;
        }
        return value;
    }

    private static Object objectCall(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "SmsClient";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> throw new SmsClientException(0, null, null);
        };
    }

    /** Holds the class-path lookup next to the handler so the path stays on {@code SmsApi}. */
    private static final class SmsApiPaths {
        private SmsApiPaths() {
        }

        static Path classPath(Method method) {
            Path direct = method.getDeclaringClass().getAnnotation(Path.class);
            if (direct != null) {
                return direct;
            }
            for (Class<?> iface : method.getDeclaringClass().getInterfaces()) {
                Path found = iface.getAnnotation(Path.class);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }
}
