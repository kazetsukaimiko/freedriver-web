package io.freedriver.keycloak.sms;

import org.keycloak.models.GroupModel;
import org.keycloak.models.UserModel;

import java.util.regex.Pattern;

/**
 * Account checks that run after sms verifies a code. Phone sign-in succeeds for an
 * enabled user whose {@code phone} attribute equals the verified number and who is a
 * direct member of the top-level group {@code phone-sign-in}, including when
 * that user holds {@code portal-admin} or a {@code realm-management} role.
 * Any exception during the lookup denies.
 */
final class PhoneSignInPolicy {

    static final String PHONE_ATTRIBUTE = "phone";
    static final String GROUP = "phone-sign-in";

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
            return user.getGroupsStream().anyMatch(PhoneSignInPolicy::isSignInGroup);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isSignInGroup(GroupModel group) {
        return GROUP.equals(group.getName()) && group.getParentId() == null;
    }
}
