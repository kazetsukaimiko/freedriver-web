package io.freedriver.app.invites;

/**
 * The invite-page agreement, character for character. The page stores this text.
 * The sms copy is {@code io.freedriver.sms.phones.InviteWording}.
 */
public final class InviteWording {

    public static final String TEXT =
            "By tapping Confirm, you agree to receive a one-time sign-in code by text from Freedriver. "
                    + "Message and data rates may apply. Reply STOP to opt out.";

    private InviteWording() {
    }
}
