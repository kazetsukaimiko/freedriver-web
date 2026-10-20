package io.freedriver.app.phones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.app.invites.InviteUpstreamException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Calls the sms service. The shared secret never goes to the browser. */
@ApplicationScoped
public class HttpSmsPhoneList implements SmsPhoneList {

    private static final TypeReference<PhoneListBody> LIST = new TypeReference<>() {
    };

    private final HttpClient http;
    private final ObjectMapper json;
    private final String base;
    private final String secret;

    @Inject
    public HttpSmsPhoneList(ObjectMapper json,
                            @ConfigProperty(name = "freedriver.sms.base-url", defaultValue = "http://sms:8080") String base,
                            @ConfigProperty(name = "sms.otp.shared-secret", defaultValue = "") String secret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json, base, secret);
    }

    HttpSmsPhoneList(HttpClient http, ObjectMapper json, String base, String secret) {
        this.http = http;
        this.json = json;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.secret = secret == null ? "" : secret;
    }

    @Override
    public List<PhoneRow> list() {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/sms/phones")).GET());
        try {
            return json.readValue(response.body(), LIST).phones();
        } catch (Exception e) {
            throw new InviteUpstreamException("sms phone list was not readable", e);
        }
    }

    @Override
    public boolean remove(String phone) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/sms/phones/remove"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"phone\":\"" + phone + "\"}")));
        try {
            return json.readTree(response.body()).path("removed").asBoolean(false);
        } catch (Exception e) {
            throw new InviteUpstreamException("sms remove was not readable", e);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        if (secret.isBlank()) {
            throw new InviteUpstreamException("sms shared secret is unset");
        }
        try {
            HttpResponse<String> response = http.send(request
                            .header("X-Freedriver-Sms-Secret", secret)
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new InviteUpstreamException("sms answered " + response.statusCode());
            }
            return response;
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw new InviteUpstreamException("sms phone list failed", e);
        }
    }

    record PhoneListBody(List<PhoneRow> phones) {
    }
}
