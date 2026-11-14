package io.freedriver.app.instances;

import io.freedriver.app.metrics.BlockingIo;
import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.mqtt.MqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * House users through the {@code freedriver-instances} Keycloak admin client.
 * The create {@code Location} id joins {@code mqtt-instance} and deletes a user that stops short.
 * That id is not stored. Reset replaces the credential and does not read the previous password.
 */
@BlockingIo
@ApplicationScoped
public class KeycloakInstanceIdentity implements InstanceIdentity {

    static final String PUBLISH_ATTRIBUTE = "mqtt_publ_topic";
    static final String SUBSCRIBE_ATTRIBUTE = "mqtt_subs_topic";
    static final String LABEL_ATTRIBUTE = "label";

    private static final int PAGE = 100;

    private final Supplier<Keycloak> admin;
    private final String realm;
    private final boolean configured;

    @Inject
    KeycloakInstanceIdentity(Instance<Keycloak> admin,
                             @ConfigProperty(name = "quarkus.keycloak.admin-client.realm") String realm,
                             @ConfigProperty(name = "freedriver.instances.client-secret") Optional<String> clientSecret) {
        this(admin::get, realm, clientSecret.filter(secret -> !secret.isBlank()).isPresent());
    }

    KeycloakInstanceIdentity(Supplier<Keycloak> admin, String realm, boolean configured) {
        this.admin = admin;
        this.realm = realm;
        this.configured = configured;
    }

    @Override
    public KeycloakUserResult create(KeycloakUser user) {
        UUID id = requireHouse(user);
        if (!configured) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        RealmResource keycloak = keycloak();
        String locationId;
        try (Response created = keycloak.users().create(representation(user))) {
            if (created.getStatus() == 409) {
                throw new UsernameTakenException();
            }
            locationId = created.getStatus() == 201 ? locationId(created.getLocation()) : null;
        } catch (UsernameTakenException taken) {
            throw taken;
        } catch (RuntimeException failure) {
            locationId = null;
        }
        if (locationId == null) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        try {
            if (!attributesPresent(user, keycloak.users().get(locationId).toRepresentation())) {
                throw new BrokerRefusedException();
            }
            String groupId = groupId(keycloak);
            if (groupId == null) {
                throw new BrokerRefusedException();
            }
            keycloak.users().get(locationId).joinGroup(groupId);
        } catch (RuntimeException ex) {
            delete(keycloak, locationId);
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
        if (!configured) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        RealmResource keycloak = keycloak();
        String locationId;
        try {
            locationId = refusing(() -> memberId(keycloak, id.toString()));
        } catch (BrokerRefusedException ex) {
            MqttLog.house(id, "refused");
            throw ex;
        }
        if (locationId == null) {
            throw new InstanceNotFoundException(id.toString());
        }
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(reset.password());
        password.setTemporary(false);
        try {
            keycloak.users().get(locationId).resetPassword(password);
        } catch (RuntimeException ex) {
            MqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        MqttLog.house(id, "reset");
    }

    @Override
    public List<InstanceSummary> list() {
        if (!configured) {
            throw new BrokerRefusedException();
        }
        RealmResource keycloak = keycloak();
        return refusing(() -> {
            String groupId = groupId(keycloak);
            if (groupId == null) {
                throw new BrokerRefusedException();
            }
            List<InstanceSummary> summaries = new ArrayList<>();
            Set<String> scanned = new HashSet<>();
            int first = 0;
            while (true) {
                List<UserRepresentation> page = keycloak.groups().group(groupId).members(first, PAGE, false);
                if (page == null || page.isEmpty()) {
                    break;
                }
                boolean progressed = false;
                for (UserRepresentation member : page) {
                    String username = member.getUsername() == null ? "" : member.getUsername();
                    if (!scanned.add(username)) {
                        continue;
                    }
                    progressed = true;
                    if (canonical(username) != null) {
                        summaries.add(new InstanceSummary(username, labelOf(member)));
                    }
                }
                if (!progressed || page.size() < PAGE) {
                    break;
                }
                first += page.size();
            }
            return List.copyOf(summaries);
        });
    }

    private static UserRepresentation representation(KeycloakUser user) {
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(user.password());
        password.setTemporary(false);
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        if (user.label() != null) {
            attributes.put(LABEL_ATTRIBUTE, List.of(user.label()));
        }
        attributes.put(PUBLISH_ATTRIBUTE, List.of(user.publishTopic()));
        attributes.put(SUBSCRIBE_ATTRIBUTE, List.of(user.subscribeTopic()));
        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(user.username());
        representation.setEnabled(true);
        representation.setCredentials(List.of(password));
        representation.setAttributes(attributes);
        return representation;
    }

    private static boolean attributesPresent(KeycloakUser user, UserRepresentation stored) {
        if (!user.username().equals(stored.getUsername())) {
            return false;
        }
        if (!one(stored, PUBLISH_ATTRIBUTE, user.publishTopic()) || !one(stored, SUBSCRIBE_ATTRIBUTE, user.subscribeTopic())) {
            return false;
        }
        if (user.label() == null) {
            return labelOf(stored) == null;
        }
        return one(stored, LABEL_ATTRIBUTE, user.label());
    }

    /** The one exact username match that is also in the top-level {@code mqtt-instance}, or null. */
    private static String memberId(RealmResource keycloak, String username) {
        String locationId = null;
        for (UserRepresentation found : keycloak.users().search(username, true)) {
            if (!username.equals(found.getUsername())) {
                continue;
            }
            if (locationId != null) {
                return null;
            }
            locationId = found.getId() == null ? "" : found.getId();
        }
        if (locationId == null || locationId.isBlank()) {
            return null;
        }
        boolean member = keycloak.users().get(locationId).groups().stream()
                .anyMatch(group -> GROUP.equals(group.getName()) && !hasParent(group));
        return member ? locationId : null;
    }

    /** The one top-level {@code mqtt-instance} group, or null. */
    private static String groupId(RealmResource keycloak) {
        String found = null;
        for (GroupRepresentation group : keycloak.groups().groups(GROUP, null, null)) {
            if (!GROUP.equals(group.getName()) || hasParent(group)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = group.getId() == null ? "" : group.getId();
        }
        return found == null || found.isBlank() ? null : found;
    }

    private static void delete(RealmResource keycloak, String locationId) {
        try (Response ignored = keycloak.users().delete(locationId)) {
            // The house is still refused. A failed delete must not hide that.
        } catch (RuntimeException ignored) {
            // The house is still refused. A failed delete must not hide that.
        }
    }

    private RealmResource keycloak() {
        return admin.get().realm(realm);
    }

    /** A Keycloak error status or transport failure is a refusal. */
    private static <T> T refusing(Supplier<T> call) {
        try {
            return call.get();
        } catch (WebApplicationException | ProcessingException failure) {
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

    private static String locationId(URI location) {
        String path = location == null || location.getRawPath() == null ? "" : location.getRawPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1 || location.getRawQuery() != null) {
            return null;
        }
        String id = path.substring(slash + 1);
        return id.isBlank() ? null : id;
    }

    private static boolean one(UserRepresentation user, String name, String expected) {
        List<String> values = user.getAttributes() == null ? null : user.getAttributes().get(name);
        return values != null && values.size() == 1 && expected.equals(values.getFirst());
    }

    private static String labelOf(UserRepresentation user) {
        List<String> values = user.getAttributes() == null ? null : user.getAttributes().get(LABEL_ATTRIBUTE);
        String label = values == null || values.isEmpty() || values.getFirst() == null ? "" : values.getFirst();
        String trimmed = label.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasParent(GroupRepresentation group) {
        return group.getParentId() != null && !group.getParentId().isBlank();
    }
}
