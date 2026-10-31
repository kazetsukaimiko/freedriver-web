package io.freedriver.sms.client;

import java.util.regex.Pattern;

/**
 * Per-call correlation id for one OTP send. Keycloak sets it on the thread before
 * {@code POST /sms/send}; the HTTP client copies it to {@link SmsClientConfig#CORRELATION_HEADER}.
 * Never holds the shared secret or a phone number.
 */
public final class SmsCallContext {

    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}");
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    private SmsCallContext() {
    }

    public static void setCorrelationId(String id) {
        String usable = usable(id);
        if (usable == null) {
            CORRELATION_ID.remove();
            return;
        }
        CORRELATION_ID.set(usable);
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }

    static String correlationId() {
        return CORRELATION_ID.get();
    }

    static String usable(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
