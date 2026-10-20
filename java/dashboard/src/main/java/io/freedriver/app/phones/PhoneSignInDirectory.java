package io.freedriver.app.phones;

/** Keycloak membership for phone sign-in. The invite service account is the only caller. */
public interface PhoneSignInDirectory {

    /** @return true when a user was taken out of {@code phone-sign-in} */
    boolean removeFromPhoneSignIn(String phone);
}
