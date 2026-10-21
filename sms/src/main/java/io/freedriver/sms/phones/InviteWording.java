package io.freedriver.sms.phones;

/**
 * The invite-page agreement, character for character. The page and
 * {@code POST /sms/invite/send} both use this value. The dashboard copy is
 * {@code io.freedriver.app.invites.InviteWording}.
 */
public final class InviteWording {

    public static final String TEXT =
            "By tapping Confirm, you agree to receive a one-time sign-in code by text from Freedriver. "
                    + "Message and data rates may apply. Reply STOP to opt out.";

    private InviteWording() {
    }
}
