package io.freedriver.app.instances;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(HouseRepositoryTest.MongoProfile.class)
class HouseRepositoryTest {

    public static class MongoProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.mongodb.devservices.enabled", "true",
                    "quarkus.mongodb.devservices.image-name", "mongo:8.0",
                    // Same workaround as the compose service. Without it, mongo:8.0
                    // exits before it accepts connections on the CI kernel.
                    "quarkus.mongodb.devservices.container-env.GLIBC_TUNABLES", "glibc.pthread.rseq=1");
        }
    }

    @Inject
    HouseRepository houses;

    @Test
    void saveAndLoadOneHouse() {
        String secret = "broker-secret-not-for-logs";
        House house = new House();
        house.id = "123e4567-e89b-12d3-a456-426614174000";
        house.label = "home";
        house.autonomyUsername = "autonomy";
        house.brokerSecret = secret;

        houses.save(house);

        House loaded = houses.load(house.id).orElseThrow();
        assertEquals(house.id, loaded.id);
        assertEquals("home", loaded.label);
        assertEquals("autonomy", loaded.autonomyUsername);
        assertEquals(secret, loaded.brokerSecret);
        assertFalse(house.toString().contains(secret));
        assertFalse(loaded.toString().contains(secret));
        assertTrue(loaded.toString().contains(house.id));
    }

    @Test
    void rejectsAnUppercaseUuid() {
        House house = new House();
        house.id = "123E4567-E89B-12D3-A456-426614174000";
        house.autonomyUsername = "autonomy";
        house.brokerSecret = "broker-secret-not-for-logs";
        assertThrows(IllegalArgumentException.class, () -> houses.save(house));
    }
}
