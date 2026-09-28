package io.freedriver.sms.api;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.Valid;

/**
 * {@code {"type":"otp","phone":"+1XXXXXXXXXX"}}: text a sign-in code to the number. On the seeded
 * number's first sign-in the body also carries {@code "agreement":{"wording":"..."}}, the wording
 * shown under the number field, which is stored before the code is sent.
 */
public record OtpSendRequest(@UsPhoneNumber String phone, @Valid AgreementShown agreement) implements SendRequest {

    @Override
    public SendType type() {
        return SendType.OTP;
    }
}
