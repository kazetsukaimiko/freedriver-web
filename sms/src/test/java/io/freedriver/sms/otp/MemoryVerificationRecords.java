package io.freedriver.sms.otp;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Verification rows for the test profile. */
@Alternative
@Priority(1)
@ApplicationScoped
public class MemoryVerificationRecords implements VerificationRecords {

    private final ConcurrentHashMap<String, Open> rows = new ConcurrentHashMap<>();

    @Override
    public String replace(String phone, Instant startedAt, Instant expiresAt) {
        Open previous = rows.put(phone, new Open(startedAt, expiresAt, null, false));
        return previous == null ? null : previous.sid();
    }

    @Override
    public void rememberSid(String phone, String sid) {
        rows.computeIfPresent(phone, (ignored, current) -> current.withSid(sid));
    }

    @Override
    public Open read(String phone) {
        return rows.get(phone);
    }

    @Override
    public void dropSid(String phone) {
        rows.computeIfPresent(phone, (ignored, current) -> current.withoutSid());
    }

    @Override
    public void exhaust(String phone) {
        rows.computeIfPresent(phone, (ignored, current) -> current.exhaust());
    }

    @Override
    public void clear(String phone) {
        rows.remove(phone);
    }

    @Override
    public void clearAll() {
        rows.clear();
    }
}
