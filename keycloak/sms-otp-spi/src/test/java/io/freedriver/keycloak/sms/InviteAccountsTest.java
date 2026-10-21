package io.freedriver.keycloak.sms;

import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InviteAccountsTest {

    private static final String PHONE = "+15555550123";

    @Test
    void create_joins_phone_sign_in_and_grants_no_role() {
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
        when(user.getGroupsStream()).thenReturn(Stream.of(group), Stream.of(group), Stream.of(group));

        try (MockedStatic<RoleUtils> roles = mockStatic(RoleUtils.class)) {
            roles.when(() -> RoleUtils.getDeepUserRoleMappings(user)).thenReturn(Set.of());
            InviteAccounts.Created created = InviteAccounts.create(session, realm, PHONE, new SecureRandom());
            assertEquals(List.of("phone-sign-in"), created.groups());
            assertTrue(created.adminRoles().isEmpty());
            assertTrue(created.username().startsWith("inv"));
            assertFalse(created.username().chars().allMatch(Character::isDigit));
        }
        verify(user).setEnabled(true);
        verify(user).setSingleAttribute("phone", PHONE);
        verify(user).joinGroup(group);
        verify(user, never()).grantRole(any());
        verify(users, never()).removeUser(realm, user);
    }

    @Test
    void an_admin_user_is_left_unchanged() {
        KeycloakSession session = mock(KeycloakSession.class);
        RealmModel realm = mock(RealmModel.class);
        UserProvider users = mock(UserProvider.class);
        GroupProvider groups = mock(GroupProvider.class);
        GroupModel group = mock(GroupModel.class);
        UserModel admin = mock(UserModel.class);
        RoleModel portalAdmin = mock(RoleModel.class);
        when(session.users()).thenReturn(users);
        when(session.groups()).thenReturn(groups);
        when(users.searchForUserByUserAttributeStream(realm, "phone", PHONE)).thenReturn(Stream.of(admin));
        when(admin.getFirstAttribute("phone")).thenReturn(PHONE);
        when(admin.isEnabled()).thenReturn(true);
        when(admin.getGroupsStream()).thenReturn(Stream.of(group));
        when(groups.getGroupByName(realm, null, "phone-sign-in")).thenReturn(group);
        when(group.getName()).thenReturn("phone-sign-in");
        when(group.getParentId()).thenReturn(null);
        when(portalAdmin.getName()).thenReturn("portal-admin");
        when(portalAdmin.isClientRole()).thenReturn(false);

        try (MockedStatic<RoleUtils> roles = mockStatic(RoleUtils.class)) {
            roles.when(() -> RoleUtils.getDeepUserRoleMappings(admin)).thenReturn(Set.of(portalAdmin));
            InviteAccountException error = assertThrows(InviteAccountException.class,
                    () -> InviteAccounts.create(session, realm, PHONE, new SecureRandom()));
            assertEquals(409, error.status());
        }
        verify(admin, never()).joinGroup(any());
        verify(admin, never()).grantRole(any());
        verify(admin, never()).setSingleAttribute(any(), any());
        verify(users, never()).addUser(any(), any());
        verify(users, never()).removeUser(any(), any());
    }

    @Test
    void mask_keeps_at_most_four_digits() {
        assertEquals("***0123", InviteAccounts.mask("+15555550123"));
        assertFalse(InviteAccounts.mask("+15555550123").contains("555555"));
        assertEquals("***", InviteAccounts.mask(null));
    }
}
