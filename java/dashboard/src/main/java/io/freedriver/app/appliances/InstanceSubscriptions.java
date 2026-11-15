package io.freedriver.app.appliances;

import io.freedriver.app.instances.HouseCreated;
import io.freedriver.app.instances.InstanceIdentity;
import io.freedriver.app.instances.InstanceSummary;
import io.freedriver.app.metrics.MetricsMqttForwarder;
import io.freedriver.mqtt.MqttLog;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.interceptor.Interceptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps {@link SubscribedHouses} equal to {@link InstanceIdentity#list()}, the users in the Keycloak
 * group {@code mqtt-instance}, plus {@code freedriver.mqtt.instance-ids} in dev and test, and calls
 * the clients' follow and unfollow for the difference. Loaded at startup, refreshed every 60 seconds, and refreshed at once when this replica
 * provisions a house; {@code freedriver.mqtt.refresh} turns the two automatic refreshes off.
 * A refresh subscribes new houses and unsubscribes removed ones. A refresh whose list call fails
 * keeps the current set: it never unsubscribes on an error. A failed load at startup leaves only
 * the configured ids until a refresh succeeds.
 */
@ApplicationScoped
public class InstanceSubscriptions {

    private final InstanceIdentity identity;
    private final MqttSettings mqtt;
    private final AppliancesConfig appliances;
    private final MqttLiveClient state;
    private final MetricsMqttForwarder metrics;
    private final SubscribedHouses subscribed;

    @Inject
    public InstanceSubscriptions(InstanceIdentity identity, MqttSettings mqtt, AppliancesConfig appliances,
                                 MqttLiveClient state, MetricsMqttForwarder metrics, SubscribedHouses subscribed) {
        this.identity = identity;
        this.mqtt = mqtt;
        this.appliances = appliances;
        this.state = state;
        this.metrics = metrics;
        this.subscribed = subscribed;
    }

    /** After {@link MqttLiveClient}'s startup check. With live commands on, a failed connect stops startup. */
    void load(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent event) {
        apply(appliances.liveCommands());
    }

    @Scheduled(every = "60s", delayed = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = RefreshOff.class)
    void scheduledRefresh() {
        refresh();
    }

    void onHouseCreated(@Observes HouseCreated created) {
        if (mqtt.refresh()) {
            refresh();
        }
    }

    /** Brings the subscribed set to the current house list. Never throws. */
    public void refresh() {
        try {
            apply(false);
        } catch (RuntimeException e) {
            MqttLog.instancesRefreshFailed();
        }
    }

    private synchronized void apply(boolean failOnConnect) {
        Set<UUID> wanted = new LinkedHashSet<>(mqtt.configuredInstances());
        boolean listed;
        try {
            for (InstanceSummary house : identity.list()) {
                UUID id = canonical(house.instanceId());
                if (id != null) {
                    wanted.add(id);
                }
            }
            listed = true;
        } catch (RuntimeException e) {
            MqttLog.instancesRefreshFailed();
            listed = false;
        }
        for (UUID id : wanted) {
            if (subscribed.contains(id)) {
                continue;
            }
            // Accept the house before subscribing, so a retained state message is not refused.
            subscribed.add(id);
            try {
                state.follow(id);
            } catch (RuntimeException e) {
                subscribed.remove(id);
                MqttLog.instanceSubscription(id, "failed");
                if (failOnConnect) {
                    throw e;
                }
                // The session did not connect; the next refresh tries the remaining houses again.
                break;
            }
            metrics.follow(id);
            MqttLog.instanceSubscription(id, "subscribed");
        }
        if (!listed) {
            return;
        }
        for (UUID id : List.copyOf(subscribed.subscribed())) {
            if (wanted.contains(id)) {
                continue;
            }
            subscribed.remove(id);
            try {
                metrics.unfollow(id);
                state.unfollow(id);
            } catch (RuntimeException e) {
                // Delivery for the topic already stopped; the broker unsubscribe is best effort.
            }
            MqttLog.instanceSubscription(id, "unsubscribed");
        }
    }

    private static UUID canonical(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(raw);
            return id.toString().equals(raw) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code freedriver.mqtt.refresh=false} skips the scheduled refresh. */
    @Singleton
    public static class RefreshOff implements Scheduled.SkipPredicate {

        private final MqttSettings mqtt;

        @Inject
        public RefreshOff(MqttSettings mqtt) {
            this.mqtt = mqtt;
        }

        @Override
        public boolean test(ScheduledExecution execution) {
            return !mqtt.refresh();
        }
    }
}
