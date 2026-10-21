package io.freedriver.sms.otp;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Send time of the open verification for every number that asks for a code, listed or not.
 * A check more than {@link #TTL} after that send is expired. A new send replaces the open one.
 */
@ApplicationScoped
public class VerificationLedger {

    static final Duration TTL = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, Open> open = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.systemUTC();

    /** Tests move the clock that the 5-minute expiry reads. */
    public void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void reset() {
        open.clear();
        clock = Clock.systemUTC();
    }

    /**
     * Starts a verification now and drops the previous one.
     *
     * @return the previous Twilio sid to cancel, or null
     */
    public synchronized String replace(String phone) {
        Open previous = open.put(phone, new Open(clock.instant(), null, false));
        return previous == null ? null : previous.sid;
    }

    public synchronized void rememberSid(String phone, String sid) {
        if (!VerificationSid.matches(sid)) {
            return;
        }
        open.computeIfPresent(phone, (ignored, current) -> current.withSid(sid));
    }

    /** True when no verification is open, or the open one started more than 5 minutes ago. */
    public synchronized boolean expired(String phone) {
        Open current = open.get(phone);
        return current == null || clock.instant().isAfter(current.startedAt.plus(TTL));
    }

    /** Sid of an expired verification that still needs a Twilio cancel, or null. */
    public synchronized String peekExpiredSid(String phone) {
        Open current = open.get(phone);
        if (current == null || current.sid == null) {
            return null;
        }
        if (!clock.instant().isAfter(current.startedAt.plus(TTL))) {
            return null;
        }
        return current.sid;
    }

    public synchronized void dropSid(String phone) {
        open.computeIfPresent(phone, (ignored, current) -> current.withSid(null));
    }

    /** The check after the fifth attempt, including Twilio 60202, closes the open verification. */
    public synchronized void exhaust(String phone) {
        open.computeIfPresent(phone, (ignored, current) -> current.exhaust());
    }

    public synchronized boolean exhausted(String phone) {
        Open current = open.get(phone);
        return current != null && current.exhausted && !clock.instant().isAfter(current.startedAt.plus(TTL));
    }

    public synchronized void clear(String phone) {
        open.remove(phone);
    }

    private record Open(Instant startedAt, String sid, boolean exhausted) {

        Open withSid(String sid) {
            return new Open(startedAt, sid, exhausted);
        }

        Open exhaust() {
            return new Open(startedAt, sid, true);
        }
    }
}
