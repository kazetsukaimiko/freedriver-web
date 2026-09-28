package io.freedriver.sms.security;

import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window counters per number: sends, and wrong codes. Counted the same for every number. */
@ApplicationScoped
public class PhoneRateLimiter {

    private final Clock clock;
    private final Duration window;
    private final int sendsPerNumber;
    private final int wrongCodesPerNumber;
    private final ConcurrentHashMap<String, Deque<Long>> sends = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> wrongCodes = new ConcurrentHashMap<>();

    @Inject
    public PhoneRateLimiter(SmsConfig config) {
        this(Clock.systemUTC(), config.rateLimit().window(),
                config.rateLimit().sendsPerNumber(), config.rateLimit().wrongCodesPerNumber());
    }

    PhoneRateLimiter(Clock clock, Duration window, int sendsPerNumber, int wrongCodesPerNumber) {
        this.clock = clock;
        this.window = window;
        this.sendsPerNumber = sendsPerNumber;
        this.wrongCodesPerNumber = wrongCodesPerNumber;
    }

    /** Counts a send for the number; false once the number has used its sends for the window. */
    public boolean tryAcquireSend(String phone) {
        Deque<Long> hits = sends.computeIfAbsent(phone, ignored -> new ArrayDeque<>());
        synchronized (hits) {
            long now = prune(hits);
            if (hits.size() >= sendsPerNumber) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }

    /** True while the number has used its wrong codes for the window. */
    public boolean codeChecksLocked(String phone) {
        Deque<Long> hits = wrongCodes.get(phone);
        if (hits == null) {
            return false;
        }
        synchronized (hits) {
            prune(hits);
            return hits.size() >= wrongCodesPerNumber;
        }
    }

    public void recordWrongCode(String phone) {
        Deque<Long> hits = wrongCodes.computeIfAbsent(phone, ignored -> new ArrayDeque<>());
        synchronized (hits) {
            hits.addLast(prune(hits));
        }
    }

    public void clearWrongCodes(String phone) {
        wrongCodes.remove(phone);
    }

    public void reset() {
        sends.clear();
        wrongCodes.clear();
    }

    private long prune(Deque<Long> hits) {
        long now = clock.millis();
        long windowMs = window.toMillis();
        while (!hits.isEmpty() && now - hits.peekFirst() >= windowMs) {
            hits.pollFirst();
        }
        return now;
    }
}
