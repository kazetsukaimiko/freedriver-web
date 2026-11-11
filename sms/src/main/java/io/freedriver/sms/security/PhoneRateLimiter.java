package io.freedriver.sms.security;

import io.freedriver.ratelimit.HitWindow;
import io.freedriver.ratelimit.MemoryHitWindow;
import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;

/**
 * Sliding-window counters per number: sends, and wrong codes. Counted the same for every number.
 * Production reads the same Redis keys from every sms process. The window expires, so a Redis
 * restart may reset it.
 */
@ApplicationScoped
public class PhoneRateLimiter {

    private final Clock clock;
    private final Duration window;
    private final int sendsPerNumber;
    private final int wrongCodesPerNumber;
    private final HitWindow hits;

    @Inject
    public PhoneRateLimiter(SmsConfig config, HitWindow hits) {
        this(Clock.systemUTC(), config.rateLimit().window(),
                config.rateLimit().sendsPerNumber(), config.rateLimit().wrongCodesPerNumber(), hits);
    }

    PhoneRateLimiter(Clock clock, Duration window, int sendsPerNumber, int wrongCodesPerNumber) {
        this(clock, window, sendsPerNumber, wrongCodesPerNumber, new MemoryHitWindow());
    }

    PhoneRateLimiter(Clock clock, Duration window, int sendsPerNumber, int wrongCodesPerNumber, HitWindow hits) {
        this.clock = clock;
        this.window = window;
        this.sendsPerNumber = sendsPerNumber;
        this.wrongCodesPerNumber = wrongCodesPerNumber;
        this.hits = hits;
    }

    /** Counts a send for the number; false once the number has used its sends for the window. */
    public boolean tryAcquireSend(String phone) {
        long now = clock.millis();
        return hits.tryAdd(sendKey(phone), now, window.toMillis(), sendsPerNumber) != null;
    }

    /** True while the number has used its wrong codes for the window. */
    public boolean codeChecksLocked(String phone) {
        long now = clock.millis();
        return hits.count(wrongKey(phone), now, window.toMillis()) >= wrongCodesPerNumber;
    }

    /**
     * Reserves one wrong-code slot for this check, or null when the number is already at its limit.
     * The slot counts immediately, so parallel checks stop at the limit.
     */
    public CodeCheck tryAcquireCodeCheck(String phone) {
        long now = clock.millis();
        String token = hits.tryAdd(wrongKey(phone), now, window.toMillis(), wrongCodesPerNumber);
        if (token == null) {
            return null;
        }
        return new CodeCheck(this, phone, token);
    }

    public void recordWrongCode(String phone) {
        hits.add(wrongKey(phone), clock.millis(), window.toMillis());
    }

    public void clearWrongCodes(String phone) {
        hits.delete(wrongKey(phone));
    }

    /** Length of the per-number window; a limited number is clear once it has passed. */
    public Duration window() {
        return window;
    }

    public void reset() {
        hits.clear();
    }

    int sendEntries() {
        return hits.size("send:", clock.millis(), window.toMillis());
    }

    int wrongCodeEntries() {
        return hits.size("wrong:", clock.millis(), window.toMillis());
    }

    private void releaseCodeCheck(String phone, String token) {
        hits.remove(wrongKey(phone), token);
    }

    private static String sendKey(String phone) {
        return "send:" + phone;
    }

    private static String wrongKey(String phone) {
        return "wrong:" + phone;
    }

    /**
     * One code check that already counts toward the number's wrong-code limit.
     * {@link #keep()} leaves the slot counted. {@link #release()} gives it back.
     */
    public static final class CodeCheck {
        private final PhoneRateLimiter limiter;
        private final String phone;
        private final String token;
        private boolean settled;

        CodeCheck(PhoneRateLimiter limiter, String phone, String token) {
            this.limiter = limiter;
            this.phone = phone;
            this.token = token;
        }

        public String phone() {
            return phone;
        }

        /** Leaves this slot counted. A wrong code uses this. */
        public void keep() {
            synchronized (this) {
                settled = true;
            }
        }

        /** Gives this slot back. */
        public void release() {
            synchronized (this) {
                if (settled) {
                    return;
                }
                settled = true;
            }
            limiter.releaseCodeCheck(phone, token);
        }
    }
}
