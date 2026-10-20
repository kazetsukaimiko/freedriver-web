package io.freedriver.sms.security;

import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counters per number: sends, and wrong codes. Counted the same for every number.
 * A number is removed once every timestamp in its window has passed, so each map holds only numbers
 * seen inside the current window.
 */
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
        evictExpired();
        while (true) {
            Deque<Long> hits = sends.computeIfAbsent(phone, ignored -> new ArrayDeque<>());
            synchronized (hits) {
                // A sweep may have removed this deque once it was empty.
                if (sends.get(phone) != hits) {
                    continue;
                }
                long now = prune(hits);
                if (hits.size() >= sendsPerNumber) {
                    return false;
                }
                hits.addLast(now);
                return true;
            }
        }
    }

    /** True while the number has used its wrong codes for the window. */
    public boolean codeChecksLocked(String phone) {
        evictExpired();
        while (true) {
            Deque<Long> hits = wrongCodes.get(phone);
            if (hits == null) {
                return false;
            }
            synchronized (hits) {
                if (wrongCodes.get(phone) != hits) {
                    continue;
                }
                prune(hits);
                if (hits.isEmpty()) {
                    wrongCodes.remove(phone, hits);
                    return false;
                }
                return hits.size() >= wrongCodesPerNumber;
            }
        }
    }

    public void recordWrongCode(String phone) {
        evictExpired();
        while (true) {
            Deque<Long> hits = wrongCodes.computeIfAbsent(phone, ignored -> new ArrayDeque<>());
            synchronized (hits) {
                if (wrongCodes.get(phone) != hits) {
                    continue;
                }
                hits.addLast(prune(hits));
                return;
            }
        }
    }

    public void clearWrongCodes(String phone) {
        wrongCodes.remove(phone);
        evictExpired();
    }

    /** Length of the per-number window; a limited number is clear once it has passed. */
    public Duration window() {
        return window;
    }

    public void reset() {
        sends.clear();
        wrongCodes.clear();
    }

    int sendEntries() {
        return sends.size();
    }

    int wrongCodeEntries() {
        return wrongCodes.size();
    }

    /** Drops numbers whose timestamps are all outside the window. */
    private void evictExpired() {
        long now = clock.millis();
        evict(sends, now);
        evict(wrongCodes, now);
    }

    private void evict(ConcurrentHashMap<String, Deque<Long>> counts, long now) {
        for (Map.Entry<String, Deque<Long>> entry : counts.entrySet()) {
            Deque<Long> hits = entry.getValue();
            synchronized (hits) {
                prune(hits, now);
                if (hits.isEmpty()) {
                    counts.remove(entry.getKey(), hits);
                }
            }
        }
    }

    private long prune(Deque<Long> hits) {
        return prune(hits, clock.millis());
    }

    private long prune(Deque<Long> hits, long now) {
        long windowMs = window.toMillis();
        while (!hits.isEmpty() && now - hits.peekFirst() >= windowMs) {
            hits.pollFirst();
        }
        return now;
    }
}
