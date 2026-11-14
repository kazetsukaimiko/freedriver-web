package io.freedriver.dispatcher;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Signed POST to the OpenBot dispatch-jobs routine. The body is the event type,
 * the time, and the hostname. An unset secret skips the POST.
 */
@ApplicationScoped
class OpenBotJobPing implements JobPing {

    static final URI WEBHOOK = URI.create(
            "https://signal.openbot.run/v1/webhooks/0ca403fc-5874-407c-8e70-38c518d442ee");

    static final String SECRET_ENV = "FREEDRIVER_DISPATCHER_OPENBOT_WEBHOOK_SECRET";

    private static final long TIMEOUT_MILLIS = 2000;

    private static final Logger LOG = Logger.getLogger(OpenBotJobPing.class);

    private final String secret;
    private final URI url;
    private final Clock clock;
    private final AtomicReference<OpenBotWebhook> webhook = new AtomicReference<>();

    @Inject
    OpenBotJobPing(DispatcherConfig config) {
        this(config.openbotWebhookSecret().orElse(""), WEBHOOK, Clock.systemUTC());
    }

    OpenBotJobPing(String secret, URI url, Clock clock) {
        requireUrl(url);
        this.secret = secret == null ? "" : secret;
        this.url = url;
        this.clock = clock;
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
        try (Response response = webhook().deliver(now.getEpochSecond(), UUID.randomUUID().toString(), body)) {
            int status = response.getStatus();
            if (status == 202 || status == 200) {
                LOG.infof("OpenBot dispatch webhook accepted for host %s (%d)", hostname, status);
            } else {
                LOG.warnf("OpenBot dispatch webhook returned %d for host %s", status, hostname);
            }
        } catch (WebhookRefused refused) {
            LOG.warnf("OpenBot dispatch webhook returned %d for host %s", refused.status(), hostname);
        } catch (ProcessingException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            LOG.warnf("OpenBot dispatch webhook failed: %s", cause.getClass().getSimpleName());
        }
    }

    /**
     * Built on first use so a skipped ping never opens a client. Redirects are not followed.
     * A second client built by a racing first call is closed.
     */
    private OpenBotWebhook webhook() {
        OpenBotWebhook built = webhook.get();
        if (built != null) {
            return built;
        }
        OpenBotWebhook fresh = QuarkusRestClientBuilder.newBuilder()
                .baseUri(url)
                .connectTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .register(new OpenBotSignature(secret))
                .register(new WebhookRefused.Mapper())
                .build(OpenBotWebhook.class);
        if (!webhook.compareAndSet(null, fresh) && fresh instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // The losing client never sent a request.
            }
        }
        return webhook.get();
    }

    static byte[] bodyBytes(String hostname, Instant occurredAt) {
        String json = "{\"type\":\"dispatch-job\",\"occurredAt\":"
                + jsonString(occurredAt.toString())
                + ",\"data\":{\"hostname\":"
                + jsonString(hostname)
                + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
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
