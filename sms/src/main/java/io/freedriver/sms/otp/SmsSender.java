package io.freedriver.sms.otp;

/**
 * Sends a sign-in code to a phone and checks a code the person typed. Implementations never
 * choose, store or log the code they send.
 */
public interface SmsSender {

    SendResult sendCode(String phone);

    CheckOutcome checkCode(String phone, String code);

    /**
     * Sets an open verification to {@code canceled}. A sid that is not a Twilio verification sid
     * is ignored. Returns whether the provider no longer has that verification.
     */
    boolean cancelVerification(String verificationSid);
}
