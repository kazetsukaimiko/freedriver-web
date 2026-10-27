package io.freedriver.app.invites;

import io.freedriver.app.phones.PhoneAlreadyExists;
import io.freedriver.app.phones.PhoneRow;
import io.freedriver.app.phones.PhoneSignInDirectory;
import io.freedriver.app.phones.SmsPhoneList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneInviteCreateTest {

    private static final String PHONE = "+15555550123";
    private static final String USERNAME = "inv" + "a".repeat(32);

    private final List<String> order = new ArrayList<>();
    private final List<PhoneRow> rows = new ArrayList<>();
    private final List<String> removedKeycloak = new ArrayList<>();
    private String whitelistedName;
    private String attached;
    private RuntimeException createFailure;

    @Test
    void lists_the_number_then_creates_the_dashboard_user() {
        PhoneInviteCreate.Result result = creator().create(PHONE, " Mom ");
        PhoneInviteCreate.Result.Listed listed = (PhoneInviteCreate.Result.Listed) result;
        assertEquals("***0123", listed.maskedPhone());
        assertFalse(listed.maskedPhone().contains("555555"));
        assertEquals(List.of("whitelist", "create", "attach"), order);
        assertEquals("Mom", whitelistedName);
        assertEquals(USERNAME, attached);
        assertEquals("dashboard", rows.getFirst().role());
        assertTrue(removedKeycloak.isEmpty());
    }

    @Test
    void a_blank_name_is_not_sent_as_the_number() {
        creator().create(PHONE, "  ");
        assertNull(whitelistedName);
        assertEquals(List.of("whitelist", "create", "attach"), order);
    }

    @Test
    void a_second_user_is_refused_and_the_new_row_is_removed() {
        createFailure = new PhoneAlreadyExists();
        PhoneInviteCreate.Result.Rejected rejected = (PhoneInviteCreate.Result.Rejected) creator().create(PHONE, null);
        assertEquals(409, rejected.status());
        assertEquals(List.of("whitelist", "create"), order);
        assertTrue(rows.isEmpty());
        assertTrue(removedKeycloak.isEmpty());
    }

    @Test
    void a_number_already_on_the_list_is_refused_before_keycloak() {
        rows.add(new PhoneRow(PHONE, "Mom", "dashboard", java.time.Instant.EPOCH, null, null));
        PhoneInviteCreate.Result.Rejected rejected = (PhoneInviteCreate.Result.Rejected) creator().create(PHONE, null);
        assertEquals(409, rejected.status());
        assertTrue(order.isEmpty());
    }

    @Test
    void only_plus_one_and_ten_digits_is_accepted() {
        PhoneInviteCreate create = creator();
        assertEquals(400, status(create.create("5555550123", null)));
        assertEquals(400, status(create.create("+1555555012", null)));
        assertEquals(400, status(create.create("+155555501234", null)));
        assertEquals(400, status(create.create("+441234567890", null)));
        assertEquals(400, status(create.create("+1 5555550123", null)));
        assertEquals(400, status(create.create(null, "Mom")));
        assertEquals(400, status(create.create(PHONE, "n".repeat(41))));
        assertTrue(order.isEmpty());
        assertTrue(rows.isEmpty());
    }

    @Test
    void a_failed_user_create_drops_the_list_row() {
        createFailure = new io.freedriver.app.invites.InviteUpstreamException("down");
        assertEquals(502, status(creator().create(PHONE, null)));
        assertTrue(rows.isEmpty());
        assertEquals(List.of("whitelist", "create"), order);
    }

    private PhoneInviteCreate creator() {
        SmsPhoneList sms = new SmsPhoneList() {
            @Override
            public List<PhoneRow> list() {
                return List.copyOf(rows);
            }

            @Override
            public boolean remove(String phone) {
                return rows.removeIf(row -> row.phone().equals(phone));
            }

            @Override
            public void requireSharedSecret() {
            }

            @Override
            public boolean whitelist(String phone, String name) {
                order.add("whitelist");
                whitelistedName = name;
                rows.add(new PhoneRow(phone, name, "dashboard", java.time.Instant.EPOCH, null, null));
                return true;
            }

            @Override
            public boolean attachUsername(String phone, String username) {
                order.add("attach");
                attached = username;
                return true;
            }

            @Override
            public InviteText sendInviteCode(String phone, UUID inviteId) {
                throw new UnsupportedOperationException();
            }
        };
        PhoneSignInDirectory keycloak = new PhoneSignInDirectory() {
            @Override
            public boolean removeFromPhoneSignIn(String phone) {
                removedKeycloak.add(phone);
                return true;
            }

            @Override
            public Created createPhoneSignInUser(String phone) {
                order.add("create");
                if (createFailure != null) {
                    throw createFailure;
                }
                return new Created(USERNAME, List.of("phone-sign-in"), List.of());
            }
        };
        return new PhoneInviteCreate(sms, keycloak);
    }

    private static int status(PhoneInviteCreate.Result result) {
        return ((PhoneInviteCreate.Result.Rejected) result).status();
    }
}
