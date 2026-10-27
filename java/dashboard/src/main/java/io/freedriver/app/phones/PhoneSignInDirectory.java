package io.freedriver.app.phones;

import java.util.List;

/** Keycloak membership for phone sign-in. The invite service account is the only caller. */
public interface PhoneSignInDirectory {

    /** @return true when a user was taken out of {@code phone-sign-in} */
    boolean removeFromPhoneSignIn(String phone);

    /**
     * Creates the user with the phone attribute, membership in {@code phone-sign-in}, and the
     * realm role {@code dashboard}. The invite-account resource grants that role. This caller
     * does not grant a role. {@code adminRoles} is empty when the user holds neither
     * {@code portal-admin} nor a realm-management role.
     */
    default Created createPhoneSignInUser(String phone) {
        throw new UnsupportedOperationException();
    }

    record Created(String username, List<String> groups, List<String> adminRoles) {
    }
}
