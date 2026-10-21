package io.freedriver.sms.model;

/** {@code {"verified":{"type":"otp","phone":"+1XXXXXXXXXX"}}}: the code matched. */
public record VerifiedResponse(SmsTarget verified) {

    public static VerifiedResponse otp(String phone) {
        return new VerifiedResponse(SmsTarget.otp(phone));
    }
}
