package io.freedriver.keycloak.sms;

/**
 * The #168 sign-in agreement, character for character.
 * The phone page and {@code POST /sms/send} both use this value.
 */
public final class SignInAgreement {

    public static final String WORDING =
            "By tapping Send code, you agree to receive a one-time sign-in code by text from Freedriver. "
                    + "Message and data rates may apply. Reply STOP to opt out.";

    private SignInAgreement() {}
}
