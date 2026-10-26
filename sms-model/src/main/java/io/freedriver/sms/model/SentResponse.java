package io.freedriver.sms.model;

/**
 * {@code {"sent":{"type":"otp","phone":"+1XXXXXXXXXX"}}} for a code, or
 * {@code {"sent":{"type":"invite","phone":"+1XXXXXXXXXX"}}} for an invitation text.
 */
public record SentResponse(SmsTarget sent) {

    public static SentResponse otp(String phone) {
        return new SentResponse(SmsTarget.otp(phone));
    }

    public static SentResponse invite(String phone) {
        return new SentResponse(SmsTarget.invite(phone));
    }
}
