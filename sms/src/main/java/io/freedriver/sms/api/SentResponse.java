package io.freedriver.sms.api;

/** {@code {"sent":{"type":"otp","phone":"+1XXXXXXXXXX"}}}, the same for every well-formed number. */
public record SentResponse(SmsTarget sent) {

    public static SentResponse otp(String phone) {
        return new SentResponse(SmsTarget.otp(phone));
    }
}
