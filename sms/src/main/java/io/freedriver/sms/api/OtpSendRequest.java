package io.freedriver.sms.api;

import io.freedriver.sms.validation.UsPhoneNumber;

/** {@code {"type":"otp","phone":"+1XXXXXXXXXX"}}: text a sign-in code to the number. */
public record OtpSendRequest(@UsPhoneNumber String phone) implements SendRequest {

    @Override
    public SendType type() {
        return SendType.OTP;
    }
}
