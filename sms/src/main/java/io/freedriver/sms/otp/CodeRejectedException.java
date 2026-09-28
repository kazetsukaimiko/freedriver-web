package io.freedriver.sms.otp;

/**
 * A code check that did not sign in: unlisted number, no pending code, expired code or wrong code.
 * All of them are this one exception so callers see one answer.
 */
public class CodeRejectedException extends RuntimeException {

    public CodeRejectedException() {
        super(null, null, false, false);
    }
}
