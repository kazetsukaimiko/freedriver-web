package io.freedriver.sms.phones;

/** Where the person gave the agreement. */
public enum ConsentSource {
    /** The invite page, when the person added their own number (#167). Removed in #521; earlier records keep it. */
    INVITE_PAGE,
    /** The first sign-in of the number seeded from server config (#168). */
    SEEDED_FIRST_SIGN_IN,
    /** Continue on the login form, for a listed number that had no agreement yet. */
    SIGN_IN_FORM
}
