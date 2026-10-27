package io.freedriver.keycloak.sms;

import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.RoleUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates the Keycloak user for an invite. The user gets the {@code phone} attribute, direct
 * membership in the top-level {@code phone-sign-in} group, and the realm role {@code dashboard}.
 * No password. A phone that already belongs to a user is refused. This class never grants
 * {@code portal-admin} or a realm-management role.
 */
final class InviteAccounts {

    static final String PHONE = "phone";
    static final String GROUP = "phone-sign-in";
    static final String DASHBOARD = "dashboard";
    private static final String PORTAL_ADMIN = "portal-admin";

    private static final Logger LOG = Logger.getLogger(InviteAccounts.class.getName());

    private InviteAccounts() {
    }

    static Created create(KeycloakSession session, RealmModel realm, String phone, SecureRandom random) {
        if (phone == null || !phone.matches("\\+1[0-9]{10}")) {
            throw new InviteAccountException(400);
        }
        List<UserModel> found = session.users()
                .searchForUserByUserAttributeStream(realm, PHONE, phone)
                .filter(user -> phone.equals(user.getFirstAttribute(PHONE)))
                .toList();
        if (!found.isEmpty()) {
            LOG.log(Level.WARNING, "Invite account refused for {0}: phone already has a user", mask(phone));
            throw new InviteAccountException(409);
        }
        GroupModel group = session.groups().getGroupByName(realm, null, GROUP);
        if (group == null || group.getParentId() != null || !GROUP.equals(group.getName())) {
            LOG.log(Level.SEVERE, "phone-sign-in group was not found");
            throw new InviteAccountException(503);
        }
        RoleModel dashboard = realm.getRole(DASHBOARD);
        if (dashboard == null || dashboard.isClientRole() || !DASHBOARD.equals(dashboard.getName())) {
            LOG.log(Level.SEVERE, "dashboard role was not found");
            throw new InviteAccountException(503);
        }
        String username = username(session, realm, random);
        UserModel user = session.users().addUser(realm, username);
        user.setEnabled(true);
        user.setSingleAttribute(PHONE, phone);
        user.joinGroup(group);
        try {
            if (!acceptable(user, phone) || user.getGroupsStream().filter(InviteAccounts::isSignInGroup).count() != 1) {
                throw new InviteAccountException(409);
            }
            user.grantRole(dashboard);
            boolean admin = holdsAdminRole(user);
            if (admin || !user.hasRole(dashboard)) {
                throw new InviteAccountException(admin ? 409 : 503);
            }
            Created created = created(user);
            LOG.log(Level.INFO, "Invite account created for {0}", mask(phone));
            return created;
        } catch (InviteAccountException e) {
            session.users().removeUser(realm, user);
            LOG.log(Level.WARNING, "Invite account rolled back for {0}", mask(phone));
            throw e;
        } catch (RuntimeException e) {
            session.users().removeUser(realm, user);
            LOG.log(Level.WARNING, "Invite account rolled back for {0}", mask(phone));
            throw new InviteAccountException(503);
        }
    }

    private static boolean acceptable(UserModel user, String phone) {
        if (user == null || !user.isEnabled() || !phone.equals(user.getFirstAttribute(PHONE))) {
            return false;
        }
        if (holdsAdminRole(user)) {
            return false;
        }
        return user.getGroupsStream().allMatch(group -> isSignInGroup(group) || GROUP.equals(group.getName())
                && group.getParentId() == null);
    }

    private static boolean isSignInGroup(GroupModel group) {
        return GROUP.equals(group.getName()) && group.getParentId() == null;
    }

    private static Created created(UserModel user) {
        List<String> groups = user.getGroupsStream()
                .filter(InviteAccounts::isSignInGroup)
                .map(GroupModel::getName)
                .distinct()
                .toList();
        List<String> adminRoles = new ArrayList<>();
        for (RoleModel role : RoleUtils.getDeepUserRoleMappings(user)) {
            if (privileged(role)) {
                adminRoles.add(role.getName());
            }
        }
        if (!adminRoles.isEmpty() || groups.size() != 1) {
            throw new InviteAccountException(409);
        }
        return new Created(user.getUsername(), groups, List.copyOf(adminRoles));
    }

    private static String username(KeycloakSession session, RealmModel realm, SecureRandom random) {
        for (int i = 0; i < 5; i++) {
            byte[] raw = new byte[16];
            random.nextBytes(raw);
            String username = "inv" + HexFormat.of().formatHex(raw);
            if (session.users().getUserByUsername(realm, username) == null) {
                return username;
            }
        }
        throw new InviteAccountException(503);
    }

    private static boolean holdsAdminRole(UserModel user) {
        try {
            return RoleUtils.getDeepUserRoleMappings(user).stream().anyMatch(InviteAccounts::privileged);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private static boolean privileged(RoleModel role) {
        if (PORTAL_ADMIN.equals(role.getName())) {
            return true;
        }
        if (!role.isClientRole()) {
            return false;
        }
        // A client role with an unreadable client counts as privileged.
        return !(role.getContainer() instanceof ClientModel client)
                || Constants.REALM_MANAGEMENT_CLIENT_ID.equals(client.getClientId());
    }

    /** At most the last four digits. */
    static String mask(String phone) {
        if (phone == null) {
            return "***";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() < 4) {
            return "***";
        }
        return "***" + digits.substring(digits.length() - 4);
    }

    record Created(String username, List<String> groups, List<String> adminRoles) {
    }
}
