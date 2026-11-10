package io.freedriver.dispatcher;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Signed POST to the OpenBot dispatch-jobs routine. The body is the event type,
 * the time, and the hostname. An unset secret skips the POST.
 */
@ApplicationScoped
class OpenBotJobPing implements JobPing {

    static final URI WEBHOOK = URI.create(
            "https://signal.openbot.run/v1/webhooks/0ca403fc-5874-407c-8e70-38c518d442ee");

    static final String SECRET_ENV = "FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET";

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final Logger LOG = Logger.getLogger(OpenBotJobPing.class);

    private final String secret;
    private final URI url;
    private final Clock clock;
    private final HttpClient http;

    @Inject
    OpenBotJobPing(
            @ConfigProperty(name = "freedriver.dispatcher.openbot-webhook-secret")
            Optional<String> secret) {
        this(secret == null ? "" : secret.orElse(""), WEBHOOK, Clock.systemUTC());
    }

    OpenBotJobPing(String secret, URI url, Clock clock) {
        requireUrl(url);
        this.secret = secret == null ? "" : secret;
        this.url = url;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public void queued(String hostname) {
        if (secret.isBlank()) {
            LOG.info("OpenBot dispatch webhook skipped; " + SECRET_ENV + " is unset");
            return;
        }
        if (hostname == null || hostname.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        byte[] body = bodyBytes(hostname, now);
        String deliveryId = UUID.randomUUID().toString();
        long timestamp = now.getEpochSecond();
        String signature = sign(secret, timestamp, deliveryId, body);
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-openbot-timestamp", Long.toString(timestamp))
                .header("x-openbot-delivery-id", deliveryId)
                .header("x-openbot-signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 202 || status == 200) {
                LOG.infof("OpenBot dispatch webhook accepted for host %s (%d)", hostname, status);
            } else {
                LOG.warnf("OpenBot dispatch webhook returned %d for host %s", status, hostname);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("OpenBot dispatch webhook interrupted");
        } catch (IOException failure) {
            LOG.warnf("OpenBot dispatch webhook failed: %s", failure.getClass().getSimpleName());
        }
    }

    static byte[] bodyBytes(String hostname, Instant occurredAt) {
        String json = "{\"type\":\"dispatch-job\",\"occurredAt\":"
                + jsonString(occurredAt.toString())
                + ",\"data\":{\"hostname\":"
                + jsonString(hostname)
                + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static String sign(String secret, long timestamp, String deliveryId, byte[] body) {
        byte[] prefix = (timestamp + "." + deliveryId + ".").getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + hex(mac.doFinal(message));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 is unavailable");
        }
    }

    static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String hex(byte[] raw) {
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    private static void requireUrl(URI url) {
        if (url == null) {
            throw new IllegalArgumentException("webhook url is required");
        }
        String scheme = url.getScheme();
        if ("https".equals(scheme)) {
            return;
        }
        String host = url.getHost();
        if ("http".equals(scheme) && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
            return;
        }
        throw new IllegalArgumentException("webhook url must be https");
    }
}
