package io.freedriver.app.instances;

import io.freedriver.dashboard.api.RateLimited;
import io.freedriver.mqtt.contract.ApplianceSchemas;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseProvisionTest {

    @Test
    void create_mints_a_password_and_only_that_houses_topics() {
        FakeIdentity identity = new FakeIdentity();
        HouseProvision houses = new HouseProvision(identity, new SecureRandom());
        ProvisionedInstance first = houses.create("  porch ");
        ProvisionedInstance second = houses.create("   ");
        assertTrue(first.password().matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(first.password(), second.password());
        assertFalse(first.toString().contains(first.password()));
        assertEquals("porch", first.label());
        assertNull(second.label());
        assertEquals("freedriver/v1/" + first.instanceId() + "/appliances", identity.created.get(0).publishTopic());
        assertEquals("freedriver/v1/" + first.instanceId() + "/commands", identity.created.get(0).subscribeTopic());
        assertEquals(InstanceIdentity.GROUP, identity.created.get(0).group());
        assertFalse(identity.created.get(1).publishTopic().contains(first.instanceId()));
        assertFalse(identity.created.get(1).subscribeTopic().contains(first.instanceId()));
        assertNotEquals(identity.created.get(0).publishTopic(), identity.created.get(1).publishTopic());
    }

    @Test
    void a_long_label_never_calls_keycloak_and_a_second_collision_refuses() {
        FakeIdentity identity = new FakeIdentity();
        HouseProvision houses = new HouseProvision(identity, new SecureRandom());
        assertThrows(InvalidLabelException.class, () -> houses.create("n".repeat(ApplianceSchemas.NAME_MAX + 1)));
        assertEquals(0, identity.creates.get());
        houses.create("n".repeat(ApplianceSchemas.NAME_MAX));
        assertEquals(1, identity.creates.get());

        identity.collisionsLeft = 2;
        assertThrows(BrokerRefusedException.class, () -> houses.create(null));
        assertEquals(3, identity.creates.get());
    }

    @Test
    void one_collision_mints_again() {
        FakeIdentity identity = new FakeIdentity();
        identity.collisionsLeft = 1;
        ProvisionedInstance created = new HouseProvision(identity, new SecureRandom()).create(null);
        assertEquals(2, identity.creates.get());
        assertEquals(created.instanceId(), identity.created.getFirst().username());
    }

    @Test
    void open_returns_a_new_password_and_not_the_previous_one() {
        FakeIdentity identity = new FakeIdentity();
        HouseProvision houses = new HouseProvision(identity, new SecureRandom());
        ProvisionedInstance created = houses.create("porch");
        ProvisionedInstance opened = houses.open(created.instanceId());
        assertEquals(created.instanceId(), opened.instanceId());
        assertEquals("porch", opened.label());
        assertNotEquals(created.password(), opened.password());
        assertEquals(opened.password(), identity.passwords.getLast());
        assertFalse(opened.toString().contains(opened.password()));
        assertThrows(InstanceNotFoundException.class, () -> houses.open("not-a-uuid"));
        assertThrows(InstanceNotFoundException.class,
                () -> houses.open(created.instanceId().toUpperCase()));
    }

    @Test
    void provision_does_not_take_the_instances_collection() {
        for (Field field : HouseProvision.class.getDeclaredFields()) {
            assertNotEquals(HouseRepository.class, field.getType(), field.getName());
            assertFalse(field.getName().equals("brokerSecret") || field.getName().equals("keycloakUserId"));
        }
        for (Field field : KeycloakInstanceIdentity.class.getDeclaredFields()) {
            assertFalse(field.getName().equals("brokerSecret") || field.getName().equals("keycloakUserId"),
                    field.getName());
        }
    }

    @Test
    void rate_limit_marks_create_and_open() throws Exception {
        Method create = InstancesApi.class.getMethod("create", CreateInstance.class);
        Method open = InstancesApi.class.getMethod("open", String.class);
        Method list = InstancesApi.class.getMethod("list");
        assertEquals("instances", create.getAnnotation(RateLimited.class).value());
        assertEquals("instances", open.getAnnotation(RateLimited.class).value());
        assertNull(list.getAnnotation(RateLimited.class));
    }

    private static final class FakeIdentity implements InstanceIdentity {
        private final AtomicInteger creates = new AtomicInteger();
        private final List<KeycloakUser> created = new ArrayList<>();
        private final List<String> passwords = new ArrayList<>();
        private int collisionsLeft;

        @Override
        public KeycloakUserResult create(KeycloakUser user) {
            creates.incrementAndGet();
            if (collisionsLeft > 0) {
                collisionsLeft--;
                throw new UsernameTakenException();
            }
            created.add(user);
            passwords.add(user.password());
            return new KeycloakUserResult(user.username());
        }

        @Override
        public void reset(ResetPassword reset) {
            passwords.add(reset.password());
        }

        @Override
        public List<InstanceSummary> list() {
            List<InstanceSummary> summaries = new ArrayList<>();
            for (KeycloakUser user : created) {
                summaries.add(new InstanceSummary(user.username(), user.label()));
            }
            return summaries;
        }
    }
}
