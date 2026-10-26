package io.freedriver.sms.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Kind of message a send request asks for. Serialized in lower case. */
public enum SendType {
    /** A one-time sign-in code sent through Twilio Verify. */
    @JsonProperty(SendType.OTP_NAME)
    OTP,
    /** An invitation text with a login link, sent through the Twilio Messages API. */
    @JsonProperty(SendType.INVITE_NAME)
    INVITE;

    public static final String OTP_NAME = "otp";
    public static final String INVITE_NAME = "invite";
}
