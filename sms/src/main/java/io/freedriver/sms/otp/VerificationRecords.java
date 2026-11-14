package io.freedriver.sms.otp;

import java.time.Instant;

/**
 * The open verification for a phone number.
 * The number names the row. {@code expiresAt} is the end of the five-minute window.
 * Callers read the row again after each write. Each write changes one field or replaces the row.
 */
interface VerificationRecords {

    /**
     * Stores a new open verification and drops the previous one.
     *
     * @return the previous Twilio sid, or null
     */
    String replace(String phone, Instant startedAt, Instant expiresAt);

    void rememberSid(String phone, String sid);

    /** The open row, or null when that number has none. */
    Open read(String phone);

    void dropSid(String phone);

    void exhaust(String phone);

    void clear(String phone);

    record Open(Instant startedAt, Instant expiresAt, String sid, boolean exhausted) {

        Open withSid(String sid) {
            return new Open(startedAt, expiresAt, sid, exhausted);
        }

        Open withoutSid() {
            return new Open(startedAt, expiresAt, null, exhausted);
        }

        Open exhaust() {
            return new Open(startedAt, expiresAt, sid, true);
        }
    }
}
