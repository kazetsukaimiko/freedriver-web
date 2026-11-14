package io.freedriver.sms.phones;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;

/**
 * When a listed number last accepted a sign-in code. This is not the agreement log.
 * Removing the number forgets the time, so a later invite can show the login link again.
 */
@ApplicationScoped
public class SignInTimes {

    private final SignInRepository signIns;
    private final Clock clock;

    @Inject
    public SignInTimes(SignInRepository signIns) {
        this(signIns, Clock.systemUTC());
    }

    SignInTimes(SignInRepository signIns, Clock clock) {
        this.signIns = signIns;
        this.clock = clock;
    }

    public Instant last(String phone) {
        return signIns.get(phone);
    }

    /** Records this sign-in. A later sign-in replaces the time. */
    public Instant record(String phone) {
        if (phone == null || !phone.matches("\\+1[0-9]{10}")) {
            throw new IllegalArgumentException("phone");
        }
        Instant when = clock.instant();
        signIns.put(phone, when);
        return when;
    }

    public void forget(String phone) {
        if (phone == null) {
            return;
        }
        signIns.delete(phone);
    }
}
