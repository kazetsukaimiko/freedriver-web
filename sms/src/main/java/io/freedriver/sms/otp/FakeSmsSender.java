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
    private final AtomicInteger deliveries = new AtomicInteger();
    private final AtomicInteger checks = new AtomicInteger();

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

    @Override
    public CheckOutcome checkCode(String phone, String code) {
        checks.incrementAndGet();
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

    /** Test hook: provider sends made since the last reset. */
    public int deliveries() {
        return deliveries.get();
    }

    /** Test hook: provider checks made since the last reset. */
    public int checks() {
        return checks.get();
    }

    public void reset() {
        pending.clear();
        deliveries.set(0);
        checks.set(0);
    }

    private record Pending(String code, Instant expiresAt) {
    }
}
