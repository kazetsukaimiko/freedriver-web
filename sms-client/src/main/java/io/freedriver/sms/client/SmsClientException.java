package io.freedriver.sms.client;

/**
 * A call that did not return the contract body. The message is only the status, a short error
 * token, and a parsed retry count. It never includes headers, the shared secret, or a body.
 */
public final class SmsClientException extends RuntimeException {

    private final int status;
    private final String error;
    private final Long retryAfterSeconds;

    public SmsClientException(int status, String error, Long retryAfterSeconds) {
        super(message(status, safe(error), retryAfterSeconds));
        this.status = status;
        this.error = safe(error);
        this.retryAfterSeconds = retryAfterSeconds;
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

    private static String message(int status, String error, Long retryAfterSeconds) {
        StringBuilder text = new StringBuilder("sms call failed: status=").append(status);
        if (error != null) {
            text.append(" error=").append(error);
        }
        if (retryAfterSeconds != null) {
            text.append(" retryAfterSeconds=").append(retryAfterSeconds);
        }
        return text.toString();
    }
}
