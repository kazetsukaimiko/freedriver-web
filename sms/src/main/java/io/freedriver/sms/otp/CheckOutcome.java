package io.freedriver.sms.otp;

/** What happened to one code check. */
public enum CheckOutcome {
    /** The code matches the pending verification. */
    APPROVED,
    /** Wrong code, or no pending verification (expired, used, or out of attempts). */
    WRONG_CODE,
    /** Twilio error 60202: the verification has used its check attempts. */
    TOO_MANY_ATTEMPTS,
    /** The provider errored or timed out. */
    FAILED
}
