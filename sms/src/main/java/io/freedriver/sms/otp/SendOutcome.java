package io.freedriver.sms.otp;

/** What happened to one code send. */
public enum SendOutcome {
    /** The provider accepted the send. */
    SENT,
    /** The number has no recorded sign-in agreement, so the provider was not called. */
    REFUSED_NO_AGREEMENT,
    /** The provider refused the send, errored or timed out. */
    FAILED
}
