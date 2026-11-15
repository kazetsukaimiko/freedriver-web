package io.freedriver.app.appliances;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The houses this process is subscribed to. {@link MqttLiveClient} and the metrics forwarder ask it
 * whether a message's house is followed; {@link InstanceSubscriptions} is the one writer.
 */
@ApplicationScoped
public class SubscribedHouses {

    private final Set<UUID> houses = ConcurrentHashMap.newKeySet();

    public boolean contains(UUID instanceId) {
        return instanceId != null && houses.contains(instanceId);
    }

    public Set<UUID> subscribed() {
        return Set.copyOf(houses);
    }

    void add(UUID instanceId) {
        houses.add(instanceId);
    }

    void remove(UUID instanceId) {
        houses.remove(instanceId);
    }
}
