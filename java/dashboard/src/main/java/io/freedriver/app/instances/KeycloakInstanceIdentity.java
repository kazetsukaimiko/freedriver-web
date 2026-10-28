package io.freedriver.app.instances;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.MqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.enterprise.context.ApplicationScoped;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * House users on the Keycloak Admin API, as the instances admin client.
 * The create {@code Location} id joins {@code mqtt-instance} and deletes a user that stops short.
 * That id is not stored. Reset replaces the credential and does not read the previous password.
 */
@ApplicationScoped
public class KeycloakInstanceIdentity implements InstanceIdentity {

    static final String PUBLISH_ATTRIBUTE = "mqtt_publ_topic";
    static final String SUBSCRIBE_ATTRIBUTE = "mqtt_subs_topic";
    static final String LABEL_ATTRIBUTE = "label";

    private static final int PAGE = 100;

    private final HttpClient http;
    private final ObjectMapper json;
    private final URI tokenEndpoint;
    private final URI admin;
    private final String clientId;
    private final String clientSecret;

    @Inject
    public KeycloakInstanceIdentity(
            ObjectMapper json,
            @ConfigProperty(name = "quarkus.oidc.auth-server-url",
                    defaultValue = "https://auth.freedriver.io/realms/freedriver") String authServer,
            @ConfigProperty(name = "freedriver.instances.client-id", defaultValue = "freedriver-instances")
            String clientId,
            @ConfigProperty(name = "freedriver.instances.client-secret") Optional<String> clientSecret) {
        this(json, authServer, clientId, clientSecret.orElse(""));
    }

    KeycloakInstanceIdentity(ObjectMapper json, String authServer, String clientId, String clientSecret) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json, authServer, clientId,
                clientSecret);
    }

    KeycloakInstanceIdentity(HttpClient http, ObjectMapper json, String authServer, String clientId,
                              String clientSecret) {
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

    @Override
    public KeycloakUserResult create(KeycloakUser user) {
        UUID id = requireHouse(user);
        if (clientSecret.isBlank()) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        String token = tokenOrRefuse(id);
        String payload = createBody(user);
        HttpResponse<String> created;
        try {
            created = send(authed(admin("users"), token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build());
        } catch (BrokerRefusedException ex) {
            MqttLog.house(id, "refused");
            throw ex;
        }
        if (created.statusCode() == 409) {
            throw new UsernameTakenException();
        }
        if (created.statusCode() != 201) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        String locationId = locationId(created);
        if (locationId == null) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        try {
            if (!attributesPresent(user, readUser(token, locationId))) {
                throw new BrokerRefusedException();
            }
            String groupId = groupId(token);
            if (groupId == null) {
                throw new BrokerRefusedException();
            }
            int joined = send(authed(admin("users/" + locationId + "/groups/" + groupId), token)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build()).statusCode();
            if (joined != 204 && joined != 200) {
                throw new BrokerRefusedException();
            }
        } catch (RuntimeException ex) {
            delete(token, locationId);
            MqttLog.house(id, "refused");
            throw ex instanceof BrokerRefusedException refused ? refused : new BrokerRefusedException();
        }
        MqttLog.house(id, "created");
        return new KeycloakUserResult(user.username());
    }

    @Override
    public void reset(ResetPassword reset) {
        UUID id = canonical(reset == null ? null : reset.instanceId());
        if (id == null || reset.password() == null || reset.password().isBlank()) {
            throw new InstanceNotFoundException(reset == null ? null : reset.instanceId());
        }
        if (clientSecret.isBlank()) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        String token = tokenOrRefuse(id);
        final String locationId;
        try {
            locationId = memberId(token, id.toString());
        } catch (BrokerRefusedException ex) {
            MqttLog.house(id, "refused");
            throw ex;
        }
        if (locationId == null) {
            throw new InstanceNotFoundException(id.toString());
        }
        String body;
        try {
            body = json.writeValueAsString(Map.of(
                    "type", "password",
                    "value", reset.password(),
                    "temporary", false));
        } catch (Exception e) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        int status = send(authed(admin("users/" + locationId + "/reset-password"), token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build()).statusCode();
        if (status != 204 && status != 200) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        MqttLog.house(id, "reset");
    }

    @Override
    public List<InstanceSummary> list() {
        if (clientSecret.isBlank()) {
            throw new BrokerRefusedException();
        }
        String token = token();
        String groupId = groupId(token);
        if (groupId == null) {
            throw new BrokerRefusedException();
        }
        List<InstanceSummary> summaries = new ArrayList<>();
        Set<String> scanned = new HashSet<>();
        int first = 0;
        while (true) {
            JsonNode page = read(send(authed(admin("groups/" + groupId + "/members?briefRepresentation=false&first="
                    + first + "&max=" + PAGE), token).GET().build()));
            if (!page.isArray() || page.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (JsonNode user : page) {
                String username = user.path("username").asText("");
                if (!scanned.add(username)) {
                    continue;
                }
                progressed = true;
                if (canonical(username) == null) {
                    continue;
                }
                summaries.add(new InstanceSummary(username, labelOf(user)));
            }
            if (!progressed || page.size() < PAGE) {
                break;
            }
            first += page.size();
        }
        return List.copyOf(summaries);
    }

    private JsonNode readUser(String token, String locationId) {
        return read(send(authed(admin("users/" + locationId), token).GET().build()));
    }

    private boolean attributesPresent(KeycloakUser user, JsonNode body) {
        if (!user.username().equals(body.path("username").asText())) {
            return false;
        }
        if (!one(body, PUBLISH_ATTRIBUTE, user.publishTopic()) || !one(body, SUBSCRIBE_ATTRIBUTE, user.subscribeTopic())) {
            return false;
        }
        if (user.label() == null) {
            return !hasValue(body, LABEL_ATTRIBUTE);
        }
        return one(body, LABEL_ATTRIBUTE, user.label());
    }

    private String memberId(String token, String username) {
        JsonNode users = read(send(authed(
                admin("users?username=" + url(username) + "&exact=true"), token).GET().build()));
        if (!users.isArray()) {
            return null;
        }
        String locationId = null;
        for (JsonNode user : users) {
            if (!username.equals(user.path("username").asText())) {
                continue;
            }
            if (locationId != null) {
                return null;
            }
            locationId = user.path("id").asText("");
        }
        if (locationId == null || locationId.isBlank() || !inGroup(token, locationId)) {
            return null;
        }
        return locationId;
    }

    private boolean inGroup(String token, String locationId) {
        JsonNode groups = read(send(authed(admin("users/" + locationId + "/groups"), token).GET().build()));
        if (!groups.isArray()) {
            return false;
        }
        for (JsonNode group : groups) {
            if (GROUP.equals(group.path("name").asText()) && !hasParent(group)) {
                return true;
            }
        }
        return false;
    }

    private String groupId(String token) {
        JsonNode groups = read(send(authed(admin("groups?search=" + url(GROUP)), token).GET().build()));
        if (!groups.isArray()) {
            return null;
        }
        String found = null;
        for (JsonNode group : groups) {
            if (!GROUP.equals(group.path("name").asText()) || hasParent(group)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = group.path("id").asText("");
        }
        return found == null || found.isBlank() ? null : found;
    }

    private void delete(String token, String locationId) {
        try {
            send(authed(admin("users/" + locationId), token).DELETE().build());
        } catch (RuntimeException ignored) {
            // The house is still refused. A failed delete must not hide that.
        }
    }

    private String tokenOrRefuse(UUID id) {
        try {
            return token();
        } catch (BrokerRefusedException ex) {
            MqttLog.house(id, "refused");
            throw ex;
        }
    }

    private String token() {
        String body = "grant_type=client_credentials&client_id=" + url(clientId) + "&client_secret=" + url(clientSecret);
        JsonNode jsonBody = read(send(HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()));
        String access = jsonBody.path("access_token").asText("");
        if (access.isBlank()) {
            throw new BrokerRefusedException();
        }
        return access;
    }

    private String createBody(KeycloakUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", user.username());
        body.put("enabled", true);
        body.put("credentials", List.of(Map.of(
                "type", "password",
                "value", user.password(),
                "temporary", false)));
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        if (user.label() != null) {
            attributes.put(LABEL_ATTRIBUTE, List.of(user.label()));
        }
        attributes.put(PUBLISH_ATTRIBUTE, List.of(user.publishTopic()));
        attributes.put(SUBSCRIBE_ATTRIBUTE, List.of(user.subscribeTopic()));
        body.put("attributes", attributes);
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new BrokerRefusedException();
        }
    }

    private static UUID requireHouse(KeycloakUser user) {
        if (user == null || user.password() == null || user.password().isBlank()) {
            throw new IllegalArgumentException("house user is incomplete");
        }
        UUID id = canonical(user.instanceId());
        if (id == null || !id.toString().equals(user.username()) || !GROUP.equals(user.group())) {
            throw new IllegalArgumentException("house user is not the mqtt-instance username");
        }
        if (!ApplianceSchemas.appliancesTopic(id).equals(user.publishTopic())
                || !ApplianceSchemas.commandsTopic(id).equals(user.subscribeTopic())) {
            throw new IllegalArgumentException("house topics are not that house");
        }
        MqttBrokers.assertExactTopic(user.publishTopic());
        MqttBrokers.assertExactTopic(user.subscribeTopic());
        return id;
    }

    private static UUID canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(raw);
            return uuid.toString().equals(raw) ? uuid : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String locationId(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location").orElse("");
        int slash = location.lastIndexOf('/');
        if (slash < 0 || slash == location.length() - 1) {
            return null;
        }
        String id = location.substring(slash + 1);
        if (id.isBlank() || id.indexOf('?') >= 0) {
            return null;
        }
        return id;
    }

    private static boolean one(JsonNode user, String name, String expected) {
        JsonNode values = user.path("attributes").path(name);
        if (values.isArray()) {
            return values.size() == 1 && expected.equals(values.get(0).asText());
        }
        return expected.equals(values.asText());
    }

    private static boolean hasValue(JsonNode user, String name) {
        JsonNode values = user.path("attributes").path(name);
        if (values.isMissingNode() || values.isNull()) {
            return false;
        }
        if (values.isArray()) {
            return !values.isEmpty() && !values.get(0).asText("").isBlank();
        }
        return !values.asText("").isBlank();
    }

    private static String labelOf(JsonNode user) {
        JsonNode values = user.path("attributes").path(LABEL_ATTRIBUTE);
        String label;
        if (values.isArray()) {
            label = values.isEmpty() ? "" : values.get(0).asText("");
        } else {
            label = values.asText("");
        }
        String trimmed = label.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasParent(JsonNode group) {
        JsonNode parentId = group.get("parentId");
        if (parentId != null && !parentId.isNull() && !parentId.asText().isBlank()) {
            return true;
        }
        JsonNode parent = group.get("parent");
        return parent != null && !parent.isNull() && !(parent.isTextual() && parent.asText().isBlank());
    }

    private URI admin(String pathAndQuery) {
        return URI.create(admin.toString() + pathAndQuery);
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
            throw new BrokerRefusedException();
        }
    }

    private JsonNode read(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new BrokerRefusedException();
        }
        try {
            return json.readTree(response.body() == null || response.body().isBlank() ? "null" : response.body());
        } catch (BrokerRefusedException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerRefusedException();
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
