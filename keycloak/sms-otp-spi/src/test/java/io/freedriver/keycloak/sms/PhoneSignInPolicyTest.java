package io.freedriver.keycloak.sms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PhoneSignInPolicyTest {

    static final String PHONE = "+15555550100";

    private UserModel user;
    private final List<GroupModel> groups = new ArrayList<>();
    private final List<RoleModel> roles = new ArrayList<>();

    @BeforeEach
    void allowedUser() {
        groups.clear();
        roles.clear();
        user = mock(UserModel.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getFirstAttribute("phone")).thenReturn("+1 (555) 555-0100");
        when(user.getGroupsStream()).thenAnswer(inv -> groups.stream());
        when(user.getRoleMappingsStream()).thenAnswer(inv -> roles.stream());
        groups.add(group("phone-sign-in", null));
        roles.add(realmRole("house"));
        roles.add(clientRole("view-profile", client("account")));
    }

    static GroupModel group(String name, String parentId) {
        GroupModel group = mock(GroupModel.class);
        when(group.getName()).thenReturn(name);
        when(group.getParentId()).thenReturn(parentId);
        when(group.getRoleMappingsStream()).thenAnswer(inv -> Stream.empty());
        return group;
    }

    static RoleModel realmRole(String name) {
        RoleModel role = mock(RoleModel.class);
        when(role.getName()).thenReturn(name);
        when(role.isClientRole()).thenReturn(false);
        when(role.getContainer()).thenReturn(mock(RealmModel.class));
        return role;
    }

    static ClientModel client(String clientId) {
        ClientModel client = mock(ClientModel.class);
        when(client.getClientId()).thenReturn(clientId);
        return client;
    }

    static RoleModel clientRole(String name, ClientModel client) {
        RoleModel role = mock(RoleModel.class);
        when(role.getName()).thenReturn(name);
        when(role.isClientRole()).thenReturn(true);
        when(role.getContainer()).thenReturn(client);
        return role;
    }

    @Test
    void allowsMatchingPhoneGroupMemberWithoutAdminRoles() {
        assertTrue(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void normalizesBothSidesTheSameWay() {
        assertEquals(PHONE, PhoneSignInPolicy.normalizePhone(" +1 (555) 555-0100 "));
        assertEquals(PHONE, PhoneSignInPolicy.normalizePhone("+1.555.555.0100"));
        assertEquals("", PhoneSignInPolicy.normalizePhone(null));
        assertTrue(PhoneSignInPolicy.allows(user, "+1 555 555 0100"));
    }

    @Test
    void deniesNullAndDisabledUsers() {
        assertFalse(PhoneSignInPolicy.allows(null, PHONE));
        when(user.isEnabled()).thenReturn(false);
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesPhoneAttributeMismatch() {
        when(user.getFirstAttribute("phone")).thenReturn("+15555550199");
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesMissingOrMalformedPhoneAttribute() {
        when(user.getFirstAttribute("phone")).thenReturn(null);
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
        when(user.getFirstAttribute("phone")).thenReturn("5555550100");
        assertFalse(PhoneSignInPolicy.allows(user, "5555550100"));
        when(user.getFirstAttribute("phone")).thenReturn(PHONE);
        assertFalse(PhoneSignInPolicy.allows(user, null));
    }

    @Test
    void deniesUserOutsidePhoneSignInGroup() {
        groups.clear();
        groups.add(group("house", null));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesNestedGroupWithTheSameName() {
        groups.clear();
        GroupModel nested = group("phone-sign-in", "parent-id");
        GroupModel parent = group("house", null);
        when(nested.getParent()).thenReturn(parent);
        groups.add(nested);
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesPortalAdminRealmRole() {
        roles.add(realmRole("portal-admin"));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesPortalAdminClientRole() {
        roles.add(clientRole("portal-admin", client("freedriver-api")));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesAnyRealmManagementClientRole() {
        roles.add(clientRole("view-users", client("realm-management")));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesPortalAdminHeldThroughAGroup() {
        GroupModel admins = group("admins", null);
        RoleModel portalAdmin = realmRole("portal-admin");
        when(admins.getRoleMappingsStream()).thenAnswer(inv -> Stream.of(portalAdmin));
        groups.add(admins);
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesRealmManagementHeldThroughAComposite() {
        RoleModel composite = realmRole("house-plus");
        RoleModel manageUsers = clientRole("manage-users", client("realm-management"));
        when(composite.isComposite()).thenReturn(true);
        when(composite.getCompositesStream()).thenAnswer(inv -> Stream.of(manageUsers));
        roles.add(composite);
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesClientRoleWithUnreadableClient() {
        RoleModel orphan = mock(RoleModel.class);
        when(orphan.getName()).thenReturn("something");
        when(orphan.isClientRole()).thenReturn(true);
        when(orphan.getContainer()).thenReturn(null);
        roles.add(orphan);
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }

    @Test
    void deniesWhenAnyLookupThrows() {
        when(user.getGroupsStream()).thenThrow(new IllegalStateException("store down"));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));

        allowedUser();
        when(user.getRoleMappingsStream()).thenThrow(new IllegalStateException("store down"));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));

        allowedUser();
        when(user.getFirstAttribute("phone")).thenThrow(new IllegalStateException("store down"));
        assertFalse(PhoneSignInPolicy.allows(user, PHONE));
    }
}
