package io.freedriver.dispatcher;

import com.mongodb.client.MongoClient;
import io.freedriver.dispatcher.model.DeviceView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production job store on the Dev Services Mongo container. */
@QuarkusTest
class MongoJobStoreTest {

    @Inject
    MongoJobStore store;

    @Inject
    MongoClient mongo;

    @Inject
    OpenBotJobPing ping;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379")
    String redisUrl;

    @Test
    void a_registered_device_is_read_back_from_the_collection() {
        String deviceId = "pc-" + UUID.randomUUID();
        String agent = "agent-" + UUID.randomUUID();
        String host = deviceId + ".local";

        DeviceView registered = store.register(deviceId, host, List.of(agent));
        assertEquals(deviceId, registered.deviceId());

        DeviceView read = device(store, deviceId);
        assertEquals(host, read.hostname());
        assertEquals(List.of(agent), read.agents());
        assertEquals(deviceId, store.deviceIdFor(host, agent));
    }

    @Test
    void a_second_replica_sees_the_first_replicas_device() {
        MongoJobStore other = new MongoJobStore(mongo, database, redisUrl, ping);
        String deviceId = "pc-" + UUID.randomUUID();
        String agent = "agent-" + UUID.randomUUID();
        String host = deviceId + ".local";

        store.register(deviceId, host, List.of(agent));

        assertEquals(host, device(other, deviceId).hostname());
        assertTrue(other.listAgents().stream().anyMatch(entry -> entry.name().equals(agent) && entry.server().equals(host)));
    }

    private static DeviceView device(JobStore store, String deviceId) {
        return store.listDevices().stream()
                .filter(device -> device.deviceId().equals(deviceId))
                .findFirst()
                .orElseThrow();
    }
}
