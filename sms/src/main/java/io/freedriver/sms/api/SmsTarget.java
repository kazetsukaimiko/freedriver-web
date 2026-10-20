package io.freedriver.sms.api;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** What a response is about: the message type and the number from the request. */
@JsonPropertyOrder({"type", "phone"})
public record SmsTarget(SendType type, String phone) {

    public static SmsTarget otp(String phone) {
        return new SmsTarget(SendType.OTP, phone);
    }
}
