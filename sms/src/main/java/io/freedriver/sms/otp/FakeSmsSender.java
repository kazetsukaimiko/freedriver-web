package io.freedriver.sms.otp;

import io.freedriver.sms.phones.ConsentLedger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sender for dev and tests. Only dev and test builds contain it (see
 * {@link FakeSenderProducer}). It keeps the pending code in memory so tests can read it; it
 * never logs a code.
 */
public class FakeSmsSender extends AgreementGatedSender {

    static final Duration CODE_TTL = Duration.ofMinutes(5);

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> checksByPhone = new ConcurrentHashMap<>();
    private final AtomicInteger deliveries = new AtomicInteger();
    private final AtomicInteger checks = new AtomicInteger();
    private volatile Duration failureDelay = Duration.ZERO;
    private volatile boolean failChecks;

    public FakeSmsSender(ConsentLedger consents, Clock clock) {
        super(consents);
        this.clock = clock;
    }

    @Override
    protected SendOutcome deliver(String phone) {
        String code = "%06d".formatted(random.nextInt(1_000_000));
        pending.put(phone, new Pending(code, clock.instant().plus(CODE_TTL)));
        deliveries.incrementAndGet();
        return SendOutcome.SENT;
    }

    /** Test hook: the next checks fail after {@code delay}, as a provider error. */
    public void failChecksAfter(Duration delay) {
        failureDelay = delay == null ? Duration.ZERO : delay;
        failChecks = true;
    }

    /** Test hook: the next checks fail after {@code delay}, as a provider timeout. */
    public void timeOutChecksAfter(Duration delay) {
        failChecksAfter(delay);
    }

    @Override
    public CheckOutcome checkCode(String phone, String code) {
        checks.incrementAndGet();
        checksByPhone.computeIfAbsent(phone, ignored -> new AtomicInteger()).incrementAndGet();
        if (failChecks) {
            sleep(failureDelay);
            return CheckOutcome.FAILED;
        }
        Pending entry = pending.get(phone);
        if (entry == null || !clock.instant().isBefore(entry.expiresAt()) || !entry.code().equals(code)) {
            return CheckOutcome.WRONG_CODE;
        }
        pending.remove(phone, entry);
        return CheckOutcome.APPROVED;
    }

    /** Test hook: the code last delivered to this number. */
    public Optional<String> pendingCode(String phone) {
        return Optional.ofNullable(pending.get(phone)).map(Pending::code);
    }

    /** Test hook: makes the pending code for this number expired. */
    public void expirePending(String phone) {
        pending.computeIfPresent(phone, (ignored, entry) -> new Pending(entry.code(), clock.instant().minusSeconds(1)));
    }

    /** Test hook: provider sends made since the last reset. */
    public int deliveries() {
        return deliveries.get();
    }

    /** Test hook: provider checks made since the last reset. */
    public int checks() {
        return checks.get();
    }

    /** Test hook: provider checks for one number since the last reset. */
    public int checksFor(String phone) {
        AtomicInteger count = checksByPhone.get(phone);
        return count == null ? 0 : count.get();
    }

    public void reset() {
        pending.clear();
        checksByPhone.clear();
        deliveries.set(0);
        checks.set(0);
        failureDelay = Duration.ZERO;
        failChecks = false;
    }

    private static void sleep(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Pending(String code, Instant expiresAt) {
    }
}
