package io.freedriver.sms.phones;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

/**
 * When a listed number last accepted a sign-in code. This is not the agreement log.
 * Removing the number forgets the time, so a later invite can show the login link again.
 */
@ApplicationScoped
public class SignInTimes {

    static final String FILE = "sign-ins.json";

    private final SignInRecords records;
    private final Clock clock;

    @Inject
    public SignInTimes(SignInRecords records) {
        this(records, Clock.systemUTC());
    }

    SignInTimes(Path dataDir, ObjectMapper json, Clock clock) {
        this(new FileSignInRecords(dataDir, json), clock);
    }

    SignInTimes(SignInRecords records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    public synchronized Instant last(String phone) {
        return records.get(phone);
    }

    /** Records this sign-in. A later sign-in replaces the time. */
    public synchronized Instant record(String phone) {
        if (phone == null || !phone.matches("\\+1[0-9]{10}")) {
            throw new IllegalArgumentException("phone");
        }
        Instant when = clock.instant();
        records.put(phone, when);
        return when;
    }

    public synchronized void forget(String phone) {
        if (phone == null) {
            return;
        }
        records.delete(phone);
    }
}
