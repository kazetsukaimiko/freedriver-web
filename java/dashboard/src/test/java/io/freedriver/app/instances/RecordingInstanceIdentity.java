package io.freedriver.app.instances;

import io.quarkus.test.Mock;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test double for the instances admin client. Refusing a create does not keep the user. */
@Mock
@ApplicationScoped
public class RecordingInstanceIdentity implements InstanceIdentity {

    private final ConcurrentHashMap<String, Stored> users = new ConcurrentHashMap<>();
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicBoolean refuse = new AtomicBoolean();
    private final AtomicBoolean refuseList = new AtomicBoolean();

    /** A test profile sets this to start with Keycloak refusing the list, as when it is not up yet. */
    @ConfigProperty(name = "recording-identity.refuse-list-at-start", defaultValue = "false")
    boolean refuseListAtStart;

    @PostConstruct
    void start() {
        refuseList.set(refuseListAtStart);
    }

    public void clear() {
        users.clear();
        creates.set(0);
        refuse.set(false);
        refuseList.set(false);
    }

    public void refuse(boolean value) {
        refuse.set(value);
    }

    /** Keycloak refuses the group member list, as when it is down. */
    public void refuseList(boolean value) {
        refuseList.set(value);
    }

    /** The house user is gone from {@code mqtt-instance}. */
    public void remove(String instanceId) {
        users.remove(instanceId);
    }

    public int creates() {
        return creates.get();
    }

    public Stored user(String instanceId) {
        return users.get(instanceId);
    }

    public boolean empty() {
        return users.isEmpty();
    }

    @Override
    public KeycloakUserResult create(KeycloakUser user) {
        creates.incrementAndGet();
        if (refuse.get()) {
            throw new BrokerRefusedException();
        }
        if (users.containsKey(user.username())) {
            throw new UsernameTakenException();
        }
        users.put(user.username(), new Stored(
                user.username(), user.label(), user.publishTopic(), user.subscribeTopic(), user.group(),
                user.password()));
        return new KeycloakUserResult(user.username());
    }

    @Override
    public void reset(ResetPassword reset) {
        Stored existing = users.get(reset.instanceId());
        if (existing == null) {
            throw new InstanceNotFoundException(reset.instanceId());
        }
        if (refuse.get()) {
            throw new BrokerRefusedException();
        }
        users.put(reset.instanceId(), new Stored(
                existing.instanceId(), existing.label(), existing.publishTopic(), existing.subscribeTopic(),
                existing.group(), reset.password()));
    }

    @Override
    public List<InstanceSummary> list() {
        if (refuseList.get()) {
            throw new BrokerRefusedException();
        }
        List<InstanceSummary> summaries = new ArrayList<>();
        for (Stored user : users.values()) {
            summaries.add(new InstanceSummary(user.instanceId(), user.label()));
        }
        return List.copyOf(summaries);
    }

    public record Stored(
            String instanceId,
            String label,
            String publishTopic,
            String subscribeTopic,
            String group,
            String password) {
    }
}
