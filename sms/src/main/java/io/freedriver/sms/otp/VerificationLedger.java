package io.freedriver.sms.otp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Open verification for every number that asks for a code, listed or not.
 * A check after the row's expiry is expired. A new send replaces the open one.
 */
@ApplicationScoped
public class VerificationLedger {

    static final Duration TTL = Duration.ofMinutes(5);

    private final VerificationRecords records;
    private volatile Clock clock = Clock.systemUTC();

    /** Arc builds the client proxy with this constructor. */
    VerificationLedger() {
        this.records = null;
    }

    @Inject
    VerificationLedger(VerificationRecords records) {
        this.records = records;
    }

    /** Tests move the clock that the 5-minute expiry reads. */
    public void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void reset() {
        records.clearAll();
        clock = Clock.systemUTC();
    }

    /**
     * Starts a verification now and drops the previous one.
     *
     * @return the previous Twilio sid to cancel, or null
     */
    public synchronized String replace(String phone) {
        Instant startedAt = clock.instant();
        return records.replace(phone, startedAt, startedAt.plus(TTL));
    }

    public synchronized void rememberSid(String phone, String sid) {
        if (!VerificationSid.matches(sid)) {
            return;
        }
        records.rememberSid(phone, sid);
    }

    /** True when no verification is open, or the open one is past its expiry. */
    public synchronized boolean expired(String phone) {
        VerificationRecords.Open current = records.read(phone);
        return current == null || clock.instant().isAfter(current.expiresAt());
    }

    /** Sid of an expired verification that still needs a Twilio cancel, or null. */
    public synchronized String peekExpiredSid(String phone) {
        VerificationRecords.Open current = records.read(phone);
        if (current == null || current.sid() == null || !clock.instant().isAfter(current.expiresAt())) {
            return null;
        }
        return current.sid();
    }

    public synchronized void dropSid(String phone) {
        records.dropSid(phone);
    }

    /** The check after the fifth attempt, including Twilio 60202, closes the open verification. */
    public synchronized void exhaust(String phone) {
        records.exhaust(phone);
    }

    public synchronized boolean exhausted(String phone) {
        VerificationRecords.Open current = records.read(phone);
        return current != null && current.exhausted() && !clock.instant().isAfter(current.expiresAt());
    }

    public synchronized void clear(String phone) {
        records.clear(phone);
    }
}
