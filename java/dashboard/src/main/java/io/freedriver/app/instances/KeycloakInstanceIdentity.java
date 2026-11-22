package io.freedriver.app.instances;

import io.freedriver.app.metrics.IoTimer;
import io.freedriver.mqtt.MqttBrokers;
import io.freedriver.app.mqttlog.HouseMqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import io.micrometer.core.annotation.Timed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

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
 * House users through the {@code freedriver-instances} Keycloak admin client, called through
 * {@link InstanceUsers}.
 * The create {@code Location} id joins {@code mqtt-instance} and deletes a user that stops short.
 * That id is not stored. Reset replaces the credential and does not read the previous password.
 */
@Timed(IoTimer.BLOCKING)
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
            HouseMqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        InstanceUsers users = users();
        String locationId;
        try {
            locationId = users.create(representation(user));
        } catch (BrokerRefusedException refused) {
            HouseMqttLog.house(id, "refused");
            throw refused;
        }
        try {
            if (!attributesPresent(user, users.read(locationId))) {
                throw new BrokerRefusedException();
            }
            String groupId = groupId(users);
            if (groupId == null) {
                throw new BrokerRefusedException();
            }
            users.join(locationId, groupId);
        } catch (RuntimeException ex) {
            users.deleteQuietly(locationId);
            HouseMqttLog.house(id, "refused");
            throw ex instanceof BrokerRefusedException refused ? refused : new BrokerRefusedException();
        }
        HouseMqttLog.house(id, "created");
        return new KeycloakUserResult(user.username());
    }

    @Override
    public void reset(ResetPassword reset) {
        UUID id = canonical(reset == null ? null : reset.instanceId());
        if (id == null || reset.password() == null || reset.password().isBlank()) {
            throw new InstanceNotFoundException(reset == null ? null : reset.instanceId());
        }
        if (!configured) {
            HouseMqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        InstanceUsers users = users();
        String locationId;
        try {
            locationId = memberId(users, id.toString());
        } catch (BrokerRefusedException ex) {
            HouseMqttLog.house(id, "refused");
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
            users.resetPassword(locationId, password);
        } catch (BrokerRefusedException ex) {
            HouseMqttLog.house(id, "refused");
            throw ex;
        }
        HouseMqttLog.house(id, "reset");
    }

    @Override
    public List<InstanceSummary> list() {
        if (!configured) {
            throw BrokerRefusedException.notConfigured();
        }
        InstanceUsers users = users();
        String groupId = groupId(users);
        if (groupId == null) {
            throw BrokerRefusedException.groupMissing();
        }
        List<InstanceSummary> summaries = new ArrayList<>();
        Set<String> scanned = new HashSet<>();
        int first = 0;
        while (true) {
            List<UserRepresentation> page = users.members(groupId, first, PAGE);
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
    }

    private static UserRepresentation representation(KeycloakUser user) {
        UUID id = requireHouse(user);
        CredentialRepresentation password = new CredentialRepresentation();
        password.setType(CredentialRepresentation.PASSWORD);
        password.setValue(user.password());
        password.setTemporary(false);
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        if (user.label() != null) {
            attributes.put(LABEL_ATTRIBUTE, List.of(user.label()));
        }
        Map<String, List<String>> topics = houseTopics(id);
        attributes.put(PUBLISH_ATTRIBUTE, topics.get(PUBLISH_ATTRIBUTE));
        attributes.put(SUBSCRIBE_ATTRIBUTE, topics.get(SUBSCRIBE_ATTRIBUTE));
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
        UUID id = canonical(user.instanceId());
        Map<String, List<String>> topics = houseTopics(id);
        if (!pair(stored, PUBLISH_ATTRIBUTE, topics.get(PUBLISH_ATTRIBUTE))
                || !pair(stored, SUBSCRIBE_ATTRIBUTE, topics.get(SUBSCRIBE_ATTRIBUTE))) {
            return false;
        }
        if (user.label() == null) {
            return labelOf(stored) == null;
        }
        return one(stored, LABEL_ATTRIBUTE, user.label());
    }

    /** The one exact username match that is also in the top-level {@code mqtt-instance}, or null. */
    private static String memberId(InstanceUsers users, String username) {
        String locationId = null;
        for (UserRepresentation found : users.exactUsername(username)) {
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
        boolean member = users.groupsOf(locationId).stream()
                .anyMatch(group -> GROUP.equals(group.getName()) && !hasParent(group));
        return member ? locationId : null;
    }

    /** The one top-level {@code mqtt-instance} group, or null. */
    private static String groupId(InstanceUsers users) {
        String found = null;
        for (GroupRepresentation group : users.groupsNamed(GROUP)) {
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


    private InstanceUsers users() {
        return new InstanceUsers(admin, realm);
    }


    private static UUID requireHouse(KeycloakUser user) {
        if (user == null || user.password() == null || user.password().isBlank()) {
            throw new IllegalArgumentException("house user is incomplete");
        }
        UUID id = canonical(user.instanceId());
        if (id == null || !id.toString().equals(user.username()) || !GROUP.equals(user.group())) {
            throw new IllegalArgumentException("house user is not the mqtt-instance username");
        }
        return id;
    }

    /** Publish and subscribe topics for one house. The commands topic stays until that grant is removed. */
    @SuppressWarnings("deprecation")
    private static Map<String, List<String>> houseTopics(UUID id) {
        List<String> publish = List.of(
                ApplianceSchemas.appliancesTopic(id),
                ApplianceSchemas.scheduledTopic(id));
        List<String> subscribe = List.of(
                ApplianceSchemas.commandsTopic(id),
                ApplianceSchemas.commandListsTopic(id));
        for (String topic : publish) {
            MqttBrokers.assertExactTopic(topic);
        }
        for (String topic : subscribe) {
            MqttBrokers.assertExactTopic(topic);
        }
        return Map.of(PUBLISH_ATTRIBUTE, publish, SUBSCRIBE_ATTRIBUTE, subscribe);
    }

    /** Each stored list has length 2 and the same values as the derived pair. Order is ignored. */
    private static boolean pair(UserRepresentation user, String name, List<String> expected) {
        List<String> values = user.getAttributes() == null ? null : user.getAttributes().get(name);
        if (values == null || values.size() != expected.size() || values.contains(null)) {
            return false;
        }
        return new HashSet<>(values).equals(new HashSet<>(expected));
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
