package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhoneRemovalOrderTest {

    @Test
    void keycloak_removal_runs_before_the_sms_row_is_deleted() {
        List<String> order = new ArrayList<>();
        PhoneRemovalResource resource = resource(order, phone -> {
            order.add("keycloak");
            return true;
        });
        resource.remove(new PhoneRemovalResource.RemovePhone("+15555550123")).close();
        assertEquals(List.of("keycloak", "sms"), order);
    }

    @Test
    void keycloak_failure_leaves_the_sms_row() {
        List<String> order = new ArrayList<>();
        PhoneRemovalResource resource = resource(order, phone -> {
            order.add("keycloak");
            throw new InviteUpstreamException("invite client secret is unset");
        });
        assertThrows(InviteUpstreamException.class,
                () -> resource.remove(new PhoneRemovalResource.RemovePhone("+15555550123")));
        assertEquals(List.of("keycloak"), order);
    }

    private static PhoneRemovalResource resource(List<String> order, PhoneSignInDirectory keycloak) {
        SmsPhoneList sms = new SmsPhoneList() {
            @Override
            public List<PhoneRow> list() {
                return List.of();
            }

            @Override
            public boolean remove(String phone) {
                order.add("sms");
                return true;
            }
        };
        return new PhoneRemovalResource(sms, keycloak);
    }
}
