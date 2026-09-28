package io.freedriver.sms.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kind of message a send request asks for. Serialized in lower case. */
public enum SendType {
    /** A one-time sign-in code sent through Twilio Verify. */
    @JsonProperty(SendType.OTP_NAME)
    OTP;

    public static final String OTP_NAME = "otp";
}
