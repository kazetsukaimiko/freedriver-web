package io.freedriver.sms.otp;

import io.freedriver.sms.phones.ConsentLedger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final Map<String, String> phoneBySid = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> checksByPhone = new ConcurrentHashMap<>();
    private final List<String> cancelledSids = new CopyOnWriteArrayList<>();
    private final AtomicInteger deliveries = new AtomicInteger();
    private final AtomicInteger checks = new AtomicInteger();
    private volatile Duration failureDelay = Duration.ZERO;
    private volatile boolean failChecks;
    private volatile CheckOutcome forcedCheck;

    public FakeSmsSender(ConsentLedger consents, Clock clock) {
        super(consents);
        this.clock = clock;
    }

    @Override
    protected SendResult deliver(String phone) {
        String code = "%06d".formatted(random.nextInt(1_000_000));
        String sid = VerificationSid.random(random);
        pending.put(phone, new Pending(code, clock.instant().plus(CODE_TTL), sid));
        phoneBySid.put(sid, phone);
        deliveries.incrementAndGet();
        return new SendResult(SendOutcome.SENT, sid);
    }

    @Override
    public boolean cancelVerification(String verificationSid) {
        if (!VerificationSid.matches(verificationSid)) {
            return false;
        }
        cancelledSids.add(verificationSid);
        String phone = phoneBySid.remove(verificationSid);
        if (phone != null) {
            pending.remove(phone);
        }
        return true;
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

    /** Test hook: the next check returns this outcome, as a provider answer such as Twilio 60202. */
    public void forceNextCheck(CheckOutcome outcome) {
        forcedCheck = outcome;
    }

    @Override
    public CheckOutcome checkCode(String phone, String code) {
        checks.incrementAndGet();
        checksByPhone.computeIfAbsent(phone, ignored -> new AtomicInteger()).incrementAndGet();
        if (failChecks) {
            sleep(failureDelay);
            return CheckOutcome.FAILED;
        }
        CheckOutcome forced = forcedCheck;
        if (forced != null) {
            forcedCheck = null;
            return forced;
        }
        Pending entry = pending.get(phone);
        if (entry == null || !clock.instant().isBefore(entry.expiresAt()) || !entry.code().equals(code)) {
            return CheckOutcome.WRONG_CODE;
        }
        pending.remove(phone, entry);
        phoneBySid.remove(entry.sid());
        return CheckOutcome.APPROVED;
    }

    /** Test hook: the code last delivered to this number. */
    public Optional<String> pendingCode(String phone) {
        return Optional.ofNullable(pending.get(phone)).map(Pending::code);
    }

    /** Test hook: the verification sid last delivered to this number. */
    public Optional<String> pendingSid(String phone) {
        return Optional.ofNullable(pending.get(phone)).map(Pending::sid);
    }

    /** Test hook: verification sids this sender was asked to cancel. */
    public List<String> cancelledSids() {
        return List.copyOf(cancelledSids);
    }

    /** Test hook: makes the pending code for this number expired. */
    public void expirePending(String phone) {
        pending.computeIfPresent(phone, (ignored, entry) -> new Pending(entry.code(), clock.instant().minusSeconds(1), entry.sid()));
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
        phoneBySid.clear();
        cancelledSids.clear();
        checksByPhone.clear();
        deliveries.set(0);
        checks.set(0);
        failureDelay = Duration.ZERO;
        failChecks = false;
        forcedCheck = null;
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

    private record Pending(String code, Instant expiresAt, String sid) {
    }
}
