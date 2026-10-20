package io.freedriver.app.phones;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.app.invites.InviteUpstreamException;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Takes a user out of the {@code phone-sign-in} group with the invite service account.
 * That account cannot grant {@code portal-admin}. This class never asks it to.
 */
@ApplicationScoped
public class KeycloakPhoneSignIn implements PhoneSignInDirectory {

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI tokenEndpoint;
    private final URI admin;
    private final String clientId;
    private final String clientSecret;

    @Inject
    public KeycloakPhoneSignIn(ObjectMapper json,
                               @ConfigProperty(name = "quarkus.oidc.auth-server-url",
                                       defaultValue = "https://auth.freedriver.io/realms/freedriver") String authServer,
                               @ConfigProperty(name = "freedriver.invite.client-id", defaultValue = "freedriver-invite")
                               String clientId,
                               @ConfigProperty(name = "freedriver.invite.client-secret")
                               Optional<String> clientSecret) {
        this(json, authServer, clientId, clientSecret.orElse(""));
    }

    KeycloakPhoneSignIn(ObjectMapper json, String authServer, String clientId, String clientSecret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json, authServer, clientId,
                clientSecret);
    }

    KeycloakPhoneSignIn(HttpClient http, ObjectMapper json, String authServer, String clientId, String clientSecret) {
        this.http = http;
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        String realmBase = authServer.endsWith("/") ? authServer.substring(0, authServer.length() - 1) : authServer;
        this.tokenEndpoint = URI.create(realmBase + "/protocol/openid-connect/token");
        int realms = realmBase.indexOf("/realms/");
        if (realms < 0) {
            throw new IllegalArgumentException("auth server url has no /realms/ segment");
        }
        this.admin = URI.create(realmBase.substring(0, realms) + "/admin" + realmBase.substring(realms) + "/");
    }

    void failClosedWhenSecretBlank(@Observes StartupEvent event) {
        requireSecret(ConfigUtils.getProfiles().contains("prod"), clientSecret);
    }

    /** Prod refuses a blank invite client secret. Non-prod may start without one. */
    static void requireSecret(boolean prod, String secret) {
        if (prod && (secret == null || secret.isBlank())) {
            throw new IllegalStateException("freedriver.invite.client-secret is blank");
        }
    }

    private URI admin(String pathAndQuery) {
        return URI.create(admin.toString() + pathAndQuery);
    }

    @Override
    public boolean removeFromPhoneSignIn(String phone) {
        if (clientSecret.isBlank()) {
            throw new InviteUpstreamException("invite client secret is unset");
        }
        String token = token();
        String groupId = groupId(token);
        String userId = userId(token, phone);
        if (userId == null) {
            return false;
        }
        int status = delete(admin("users/" + userId + "/groups/" + groupId), token);
        if (status != 204 && status != 200) {
            throw new InviteUpstreamException("Keycloak left the user in phone-sign-in (" + status + ")");
        }
        return true;
    }

    private String token() {
        String body = "grant_type=client_credentials&client_id=" + url(clientId) + "&client_secret=" + url(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        JsonNode jsonBody = read(send(request));
        String access = jsonBody.path("access_token").asText("");
        if (access.isBlank()) {
            throw new InviteUpstreamException("invite service account returned no token");
        }
        return access;
    }

    private String groupId(String token) {
        JsonNode groups = read(send(authed(admin("groups?search=phone-sign-in"), token).GET().build()));
        List<String> ids = new ArrayList<>();
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                if (!"phone-sign-in".equals(group.path("name").asText()) || hasParent(group)) {
                    continue;
                }
                ids.add(group.path("id").asText());
            }
        }
        if (ids.size() != 1 || ids.get(0).isBlank()) {
            throw new InviteUpstreamException("phone-sign-in group was not found");
        }
        return ids.get(0);
    }

    private String userId(String token, String phone) {
        String query = "users?q=" + url("phone:" + phone) + "&exact=true&briefRepresentation=false";
        JsonNode users = read(send(authed(admin(query), token).GET().build()));
        if (!users.isArray() || users.isEmpty()) {
            return null;
        }
        if (users.size() != 1) {
            throw new InviteUpstreamException("more than one user has this phone attribute");
        }
        JsonNode user = users.get(0);
        if (!phoneEquals(user, phone)) {
            throw new InviteUpstreamException("phone attribute does not match");
        }
        String id = user.path("id").asText("");
        if (id.isBlank()) {
            throw new InviteUpstreamException("phone user has no id");
        }
        return id;
    }

    /** Top-level phone-sign-in only. A group with a parent is not the policy group. */
    private static boolean hasParent(JsonNode group) {
        JsonNode parentId = group.get("parentId");
        if (parentId != null && !parentId.isNull() && !parentId.asText().isBlank()) {
            return true;
        }
        JsonNode parent = group.get("parent");
        return parent != null && !parent.isNull() && !(parent.isTextual() && parent.asText().isBlank());
    }

    private static boolean phoneEquals(JsonNode user, String phone) {
        JsonNode values = user.path("attributes").path("phone");
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (phone.equals(value.asText())) {
                    return true;
                }
            }
            return false;
        }
        if (values.isMissingNode() || values.isNull()) {
            return false;
        }
        return phone.equals(values.asText());
    }

    private int delete(URI uri, String token) {
        return send(authed(uri, token).DELETE().build()).statusCode();
    }

    private HttpRequest.Builder authed(URI uri, String token) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(5));
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new InviteUpstreamException("Keycloak call failed", e);
        }
    }

    private JsonNode read(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new InviteUpstreamException("Keycloak answered " + response.statusCode());
        }
        try {
            return json.readTree(response.body());
        } catch (Exception e) {
            throw new InviteUpstreamException("Keycloak body was not json", e);
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
