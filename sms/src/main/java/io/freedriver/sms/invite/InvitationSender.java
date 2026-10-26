package io.freedriver.sms.invite;

import io.freedriver.sms.otp.SendOutcome;

/** Sends one invitation text through the Twilio Messages API. */
public interface InvitationSender {

    /** True when {@code TWILIO_MESSAGING_FROM} is set and a Messages call can be made. */
    boolean configured();

    SendOutcome send(String phone, String body);
}
