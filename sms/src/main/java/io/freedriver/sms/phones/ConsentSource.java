package io.freedriver.sms.phones;

/** Where the person gave the agreement. */
public enum ConsentSource {
    /** The invite page, when the person adds their own number (#167). */
    INVITE_PAGE,
    /** The first sign-in of the number seeded from server config (#168). */
    SEEDED_FIRST_SIGN_IN
}
