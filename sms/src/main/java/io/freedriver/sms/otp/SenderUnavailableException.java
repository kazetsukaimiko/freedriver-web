package io.freedriver.sms.otp;

/** The provider errored or timed out while checking a code. */
public class SenderUnavailableException extends RuntimeException {

    public SenderUnavailableException() {
        super(null, null, false, false);
    }
}
