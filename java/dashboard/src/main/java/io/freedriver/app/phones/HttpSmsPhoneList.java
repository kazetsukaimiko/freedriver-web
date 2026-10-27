package io.freedriver.app.phones;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.app.invites.InviteWording;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
                            @ConfigProperty(name = "sms.otp.shared-secret") Optional<String> secret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json, base,
                secret.orElse(""));
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

    @Override
    public void requireSharedSecret() {
        // The blank-secret refusal stays in send. A null request does not open a connection.
        send(null);
    }

    @Override
    public InviteText sendInviteCode(String phone, UUID inviteId) {
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(base + "/sms/invite/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(Map.of(
                        "phone", phone,
                        "wording", InviteWording.TEXT,
                        "inviteId", inviteId.toString())))));
        return switch (response.statusCode()) {
            case 200 -> InviteText.SENT;
            case 409 -> InviteText.ALREADY_LISTED;
            case 429 -> InviteText.LIMITED;
            default -> InviteText.UNAVAILABLE;
        };
    }

    @Override
    public InviteCheck checkInviteCode(String phone, String code) {
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(base + "/sms/invite/verify"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(Map.of("phone", phone, "code", code)))));
        return switch (response.statusCode()) {
            case 200 -> InviteCheck.APPROVED;
            case 400 -> InviteCheck.WRONG;
            case 429 -> InviteCheck.LIMITED;
            default -> InviteCheck.UNAVAILABLE;
        };
    }

    @Override
    public boolean addInvited(String phone, String name, String username) {
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(base + "/sms/phones/add"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(Map.of(
                        "phone", phone, "name", name, "username", username, "role", "dashboard")))));
        if (response.statusCode() / 100 != 2) {
            throw new InviteUpstreamException("sms add answered " + response.statusCode());
        }
        try {
            return json.readTree(response.body()).path("added").asBoolean(false);
        } catch (Exception e) {
            throw new InviteUpstreamException("sms add was not readable", e);
        }
    }

    @Override
    public boolean whitelist(String phone, String name) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("phone", phone);
        if (name != null && !name.isBlank()) {
            fields.put("name", name);
        }
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(base + "/sms/phones/whitelist"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(fields))));
        if (response.statusCode() / 100 != 2) {
            throw new InviteUpstreamException("sms whitelist answered " + response.statusCode());
        }
        try {
            return json.readTree(response.body()).path("added").asBoolean(false);
        } catch (Exception e) {
            throw new InviteUpstreamException("sms whitelist was not readable", e);
        }
    }

    @Override
    public boolean attachUsername(String phone, String username) {
        HttpResponse<String> response = call(HttpRequest.newBuilder(URI.create(base + "/sms/phones/attach"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(Map.of("phone", phone, "username", username)))));
        if (response.statusCode() / 100 != 2) {
            throw new InviteUpstreamException("sms attach answered " + response.statusCode());
        }
        try {
            return json.readTree(response.body()).path("attached").asBoolean(false);
        } catch (Exception e) {
            throw new InviteUpstreamException("sms attach was not readable", e);
        }
    }

    private String body(Map<String, String> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (Exception e) {
            throw new InviteUpstreamException("sms body was not writable", e);
        }
    }

    /** Like {@link #send}, but a non-2xx status is returned so the caller can tell the answers apart. */
    private HttpResponse<String> call(HttpRequest.Builder request) {
        if (secret == null || secret.isBlank()) {
            throw new InviteUpstreamException("sms shared secret is unset");
        }
        try {
            return http.send(request
                            .header("X-Freedriver-Sms-Secret", secret)
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new InviteUpstreamException("sms phone list failed", e);
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        if (secret == null || secret.isBlank()) {
            throw new InviteUpstreamException("sms shared secret is unset");
        }
        if (request == null) {
            return null;
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
