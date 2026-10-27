package io.freedriver.keycloak.sms;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.utils.RoleUtils;
import org.mockito.MockedStatic;

import java.security.SecureRandom;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InviteAccountsTest {

    private static final String PHONE = "+15555550123";

    @Test
    void create_joins_phone_sign_in_and_grants_only_dashboard() {
        Harness harness = newUser();
        RoleModel dashboard = dashboardRole(harness.realm());

        try (MockedStatic<RoleUtils> roles = mockStatic(RoleUtils.class)) {
            roles.when(() -> RoleUtils.getDeepUserRoleMappings(harness.user())).thenReturn(Set.of());
            InviteAccounts.Created created = InviteAccounts.create(
                    harness.session(), harness.realm(), PHONE, new SecureRandom());
            assertEquals(List.of("phone-sign-in"), created.groups());
            assertTrue(created.adminRoles().isEmpty());
            assertTrue(created.username().startsWith("inv"));
            assertEquals(32, created.username().length() - 3);
            assertFalse(created.username().contains("portal-admin"));
        }
        verify(harness.user()).setEnabled(true);
        verify(harness.user()).setSingleAttribute("phone", PHONE);
        verify(harness.user()).joinGroup(harness.group());
        verify(harness.user(), times(1)).grantRole(dashboard);
        verify(harness.user(), never()).credentialManager();
        verify(harness.users(), never()).removeUser(harness.realm(), harness.user());
    }

    @Test
    void a_second_user_for_the_same_phone_is_refused() {
        refuseExisting(null);
    }

    @Test
    void an_admin_user_is_left_unchanged() {
        RoleModel portalAdmin = mock(RoleModel.class);
        when(portalAdmin.getName()).thenReturn("portal-admin");
        when(portalAdmin.isClientRole()).thenReturn(false);
        refuseExisting(portalAdmin);
    }

    @Test
    void a_realm_management_user_is_left_unchanged() {
        RoleModel manage = mock(RoleModel.class);
        ClientModel client = mock(ClientModel.class);
        when(manage.getName()).thenReturn("manage-users");
        when(manage.isClientRole()).thenReturn(true);
        when(manage.getContainer()).thenReturn(client);
        when(client.getClientId()).thenReturn(Constants.REALM_MANAGEMENT_CLIENT_ID);
        refuseExisting(manage);
    }

    @Test
    void portal_admin_on_a_new_user_is_rolled_back_and_not_granted() {
        privilegedNewUserIsRolledBack(realmRole("portal-admin"));
    }

    @Test
    void realm_management_on_a_new_user_is_rolled_back_and_not_granted() {
        RoleModel manage = mock(RoleModel.class);
        ClientModel client = mock(ClientModel.class);
        when(manage.getName()).thenReturn("realm-admin");
        when(manage.isClientRole()).thenReturn(true);
        when(manage.getContainer()).thenReturn(client);
        when(client.getClientId()).thenReturn(Constants.REALM_MANAGEMENT_CLIENT_ID);
        privilegedNewUserIsRolledBack(manage);
    }

    @Test
    void a_missing_dashboard_role_creates_no_user() {
        Harness harness = newUser();
        when(harness.realm().getRole("dashboard")).thenReturn(null);
        InviteAccountException error = assertThrows(InviteAccountException.class,
                () -> InviteAccounts.create(harness.session(), harness.realm(), PHONE, new SecureRandom()));
        assertEquals(503, error.status());
        verify(harness.users(), never()).addUser(any(), any());
        verify(harness.user(), never()).grantRole(any());
    }

    @Test
    void mask_keeps_at_most_four_digits() {
        assertEquals("***0123", InviteAccounts.mask("+15555550123"));
        assertFalse(InviteAccounts.mask("+15555550123").contains("555555"));
        assertEquals("***", InviteAccounts.mask(null));
    }

    private static void refuseExisting(RoleModel role) {
        KeycloakSession session = mock(KeycloakSession.class);
        RealmModel realm = mock(RealmModel.class);
        UserProvider users = mock(UserProvider.class);
        UserModel existing = mock(UserModel.class);
        when(session.users()).thenReturn(users);
        when(users.searchForUserByUserAttributeStream(realm, "phone", PHONE)).thenReturn(Stream.of(existing));
        when(existing.getFirstAttribute("phone")).thenReturn(PHONE);
        try (MockedStatic<RoleUtils> roles = mockStatic(RoleUtils.class)) {
            roles.when(() -> RoleUtils.getDeepUserRoleMappings(existing)).thenReturn(role == null ? Set.of() : Set.of(role));
            InviteAccountException error = assertThrows(InviteAccountException.class,
                    () -> InviteAccounts.create(session, realm, PHONE, new SecureRandom()));
            assertEquals(409, error.status());
        }
        verify(existing, never()).joinGroup(any());
        verify(existing, never()).grantRole(any());
        verify(existing, never()).setSingleAttribute(any(), any());
        verify(existing, never()).credentialManager();
        verify(users, never()).addUser(any(), any());
        verify(users, never()).removeUser(any(), any());
    }

    private static void privilegedNewUserIsRolledBack(RoleModel role) {
        Harness harness = newUser();
        dashboardRole(harness.realm());
        try (MockedStatic<RoleUtils> roles = mockStatic(RoleUtils.class)) {
            roles.when(() -> RoleUtils.getDeepUserRoleMappings(harness.user())).thenReturn(Set.of(role));
            InviteAccountException error = assertThrows(InviteAccountException.class,
                    () -> InviteAccounts.create(harness.session(), harness.realm(), PHONE, new SecureRandom()));
            assertEquals(409, error.status());
        }
        verify(harness.user(), never()).grantRole(any());
        verify(harness.users()).removeUser(harness.realm(), harness.user());
    }

    private static RoleModel dashboardRole(RealmModel realm) {
        RoleModel dashboard = mock(RoleModel.class);
        when(dashboard.getName()).thenReturn("dashboard");
        when(dashboard.isClientRole()).thenReturn(false);
        when(realm.getRole("dashboard")).thenReturn(dashboard);
        return dashboard;
    }

    private static RoleModel realmRole(String name) {
        RoleModel role = mock(RoleModel.class);
        when(role.getName()).thenReturn(name);
        when(role.isClientRole()).thenReturn(false);
        return role;
    }

    private static Harness newUser() {
        KeycloakSession session = mock(KeycloakSession.class);
        RealmModel realm = mock(RealmModel.class);
        UserProvider users = mock(UserProvider.class);
        GroupProvider groups = mock(GroupProvider.class);
        GroupModel group = mock(GroupModel.class);
        UserModel user = mock(UserModel.class);
        when(session.users()).thenReturn(users);
        when(session.groups()).thenReturn(groups);
        when(users.searchForUserByUserAttributeStream(realm, "phone", PHONE)).thenReturn(Stream.empty());
        when(groups.getGroupByName(realm, null, "phone-sign-in")).thenReturn(group);
        when(group.getName()).thenReturn("phone-sign-in");
        when(group.getParentId()).thenReturn(null);
        when(users.getUserByUsername(eq(realm), any())).thenReturn(null);
        when(users.addUser(eq(realm), any())).thenReturn(user);
        when(user.isEnabled()).thenReturn(true);
        when(user.getFirstAttribute("phone")).thenReturn(PHONE);
        when(user.getUsername()).thenReturn("inv0123456789abcdef0123456789abcdef");
        when(user.getGroupsStream()).thenAnswer(invocation -> Stream.of(group));
        when(user.hasRole(any())).thenReturn(true);
        return new Harness(session, realm, users, user, group);
    }

    private record Harness(KeycloakSession session, RealmModel realm, UserProvider users, UserModel user, GroupModel group) {
    }
}
