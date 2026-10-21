package io.freedriver.sms.client;

import io.freedriver.sms.model.VerifyFailure;

/**
 * A call that did not return the contract body. The message is only the status, a short error
 * token, a verify reason, and a parsed retry count. It never includes headers, the shared secret,
 * or a body.
 */
public final class SmsClientException extends RuntimeException {

    private final int status;
    private final String error;
    private final Long retryAfterSeconds;
    private final String reason;

    public SmsClientException(int status, String error, Long retryAfterSeconds) {
        this(status, error, retryAfterSeconds, null);
    }

    public SmsClientException(int status, String error, Long retryAfterSeconds, String reason) {
        super(message(status, safe(error), safeReason(reason), retryAfterSeconds));
        this.status = status;
        this.error = safe(error);
        this.retryAfterSeconds = retryAfterSeconds;
        this.reason = safeReason(reason);
    }

    public int status() {
        return status;
    }

    /** The {@code error} token when it is a short machine code, otherwise null. */
    public String error() {
        return error;
    }

    /** Parsed {@code Retry-After} seconds, or null when the header is missing or not a count. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** {@code expired}, {@code wrong_code}, or {@code too_many_attempts}; otherwise null. */
    public String reason() {
        return reason;
    }

    private static String safe(String error) {
        if (error == null || error.length() > 32) {
            return null;
        }
        for (int i = 0; i < error.length(); i++) {
            char c = error.charAt(i);
            if (c != '-' && (c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return null;
            }
        }
        return error.isEmpty() ? null : error;
    }

    private static String safeReason(String reason) {
        if (VerifyFailure.EXPIRED.equals(reason) || VerifyFailure.WRONG_CODE.equals(reason)
                || VerifyFailure.TOO_MANY_ATTEMPTS.equals(reason)) {
            return reason;
        }
        return null;
    }

    private static String message(int status, String error, String reason, Long retryAfterSeconds) {
        StringBuilder text = new StringBuilder("sms call failed: status=").append(status);
        if (error != null) {
            text.append(" error=").append(error);
        }
        if (reason != null) {
            text.append(" reason=").append(reason);
        }
        if (retryAfterSeconds != null) {
            text.append(" retryAfterSeconds=").append(retryAfterSeconds);
        }
        return text.toString();
    }
}
