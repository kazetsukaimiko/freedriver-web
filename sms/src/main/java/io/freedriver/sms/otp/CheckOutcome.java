package io.freedriver.sms.otp;

/** What happened to one code check. */
public enum CheckOutcome {
    /** The code matches the pending verification. */
    APPROVED,
    /** Wrong code, or no pending verification (expired, used, or out of attempts). */
    WRONG_CODE,
    /** The provider errored or timed out. */
    FAILED
}
