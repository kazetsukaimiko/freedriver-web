package io.freedriver.sms.otp;

/** What a send did, plus the Twilio verification sid when the provider created one. */
public record SendResult(SendOutcome outcome, String verificationSid) {

    public static SendResult of(SendOutcome outcome) {
        return new SendResult(outcome, null);
    }
}
