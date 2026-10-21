package io.freedriver.app.phones;

import java.util.List;

/** Keycloak membership for phone sign-in. The invite service account is the only caller. */
public interface PhoneSignInDirectory {

    /** @return true when a user was taken out of {@code phone-sign-in} */
    boolean removeFromPhoneSignIn(String phone);

    /**
     * Creates the user with the phone attribute and membership in {@code phone-sign-in}.
     * The service account grants no role. {@code adminRoles} is empty when the user holds none.
     */
    default Created createPhoneSignInUser(String phone) {
        throw new UnsupportedOperationException();
    }

    record Created(String username, List<String> groups, List<String> adminRoles) {
    }
}
