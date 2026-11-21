package io.freedriver.app.instances;

import io.freedriver.app.mqttlog.HouseMqttLog;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Mints the house id and password and calls the Keycloak user stage, then fires
 * {@link HouseCreated} so this replica subscribes to the house at once.
 * Does not insert a Mongo document, and does not store {@code brokerSecret} or a Keycloak user id.
 */
@ApplicationScoped
public class HouseProvision {

    private final InstanceIdentity identity;
    private final SecureRandom random;
    private final Consumer<HouseCreated> created;

    @Inject
    public HouseProvision(InstanceIdentity identity, Event<HouseCreated> created) {
        this(identity, new SecureRandom(), created::fire);
    }

    HouseProvision(InstanceIdentity identity, SecureRandom random) {
        this(identity, random, house -> {
        });
    }

    HouseProvision(InstanceIdentity identity, SecureRandom random, Consumer<HouseCreated> created) {
        this.identity = identity;
        this.random = random;
        this.created = created;
    }

    public ProvisionedInstance create(String rawLabel) {
        String label = label(rawLabel);
        for (int attempt = 0; attempt < 2; attempt++) {
            UUID id = UUID.randomUUID();
            try {
                return createOne(id, label);
            } catch (UsernameTakenException taken) {
                if (attempt == 1) {
                    HouseMqttLog.house(id, "refused");
                    throw new BrokerRefusedException();
                }
            }
        }
        throw new BrokerRefusedException();
    }

    public ProvisionedInstance open(String rawInstanceId) {
        String instanceId = canonical(rawInstanceId);
        InstanceSummary found = null;
        for (InstanceSummary summary : identity.list()) {
            if (instanceId.equals(summary.instanceId())) {
                found = summary;
                break;
            }
        }
        if (found == null) {
            throw new InstanceNotFoundException(instanceId);
        }
        String password = password();
        identity.reset(new ResetPassword(instanceId, password));
        return new ProvisionedInstance(instanceId, found.label(), password);
    }

    public InstanceList list() {
        return new InstanceList(identity.list(), null);
    }

    private ProvisionedInstance createOne(UUID id, String label) {
        String username = id.toString();
        String password = password();
        KeycloakUserResult result = identity.create(new KeycloakUser(
                username,
                username,
                label,
                password,
                InstanceIdentity.GROUP));
        if (result == null || !username.equals(result.username())) {
            HouseMqttLog.house(id, "refused");
            throw new BrokerRefusedException();
        }
        created.accept(new HouseCreated(id));
        return new ProvisionedInstance(username, label, password);
    }

    static String label(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > ApplianceSchemas.NAME_MAX) {
            throw new InvalidLabelException();
        }
        return trimmed;
    }

    private static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InstanceNotFoundException(raw);
        }
        final UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new InstanceNotFoundException(raw);
        }
        if (!uuid.toString().equals(raw)) {
            throw new InstanceNotFoundException(raw);
        }
        return uuid.toString();
    }

    private String password() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
