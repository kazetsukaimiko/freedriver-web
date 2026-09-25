package io.freedriver.keycloak.sms;

import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.RoleUtils;

import java.util.regex.Pattern;

/**
 * Account checks that run after sms verifies a code. Phone sign-in succeeds for an
 * enabled user whose {@code phone} attribute equals the verified number, who is a
 * direct member of the top-level group {@code phone-sign-in}, and whose effective
 * roles (direct, group and composite) include neither a realm or client role named
 * {@code portal-admin} nor any {@code realm-management} client role.
 * Any exception during the lookup denies.
 */
final class PhoneSignInPolicy {

    static final String PHONE_ATTRIBUTE = "phone";
    static final String GROUP = "phone-sign-in";
    static final String PORTAL_ADMIN = "portal-admin";

    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s().-]");

    private PhoneSignInPolicy() {}

    /** Removes whitespace, dots, dashes and parentheses; returns "" for null. */
    static String normalizePhone(String raw) {
        return raw == null ? "" : SEPARATORS.matcher(raw).replaceAll("");
    }

    static boolean validPhone(String normalized) {
        return PHONE.matcher(normalized).matches();
    }

    static boolean allows(UserModel user, String verifiedPhone) {
        try {
            if (user == null || !user.isEnabled() || verifiedPhone == null) {
                return false;
            }
            String phone = normalizePhone(user.getFirstAttribute(PHONE_ATTRIBUTE));
            if (!validPhone(phone) || !phone.equals(normalizePhone(verifiedPhone))) {
                return false;
            }
            if (user.getGroupsStream().noneMatch(PhoneSignInPolicy::isSignInGroup)) {
                return false;
            }
            return RoleUtils.getDeepUserRoleMappings(user).stream().noneMatch(PhoneSignInPolicy::privileged);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isSignInGroup(GroupModel group) {
        return GROUP.equals(group.getName()) && group.getParentId() == null;
    }

    private static boolean privileged(RoleModel role) {
        if (PORTAL_ADMIN.equals(role.getName())) {
            return true;
        }
        if (!role.isClientRole()) {
            return false;
        }
        // A client role whose client cannot be read counts as privileged.
        return !(role.getContainer() instanceof ClientModel client)
                || Constants.REALM_MANAGEMENT_CLIENT_ID.equals(client.getClientId());
    }
}
