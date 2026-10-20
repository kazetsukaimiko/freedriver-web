package io.freedriver.sms.phones;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.SmsConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.Optional;

/**
 * Puts kaze's number from {@code FREEDRIVER_SEED_PHONE} on the phone list at startup, mapped to the
 * Keycloak user {@code kaze} with {@code dashboard}. A number already on the list keeps its row as
 * it is, so changing the setting only ever adds a number.
 */
@ApplicationScoped
public class PhoneListSeed {

    static final String USERNAME = "kaze";
    static final String NAME = "kaze";
    static final String ROLE = "dashboard";

    private static final Logger LOG = Logger.getLogger(PhoneListSeed.class);

    private final Optional<String> seedPhone;
    private final PhoneDirectory directory;
    private final Clock clock;

    @Inject
    public PhoneListSeed(SmsConfig config, PhoneDirectory directory) {
        this(config.seedPhone(), directory, Clock.systemUTC());
    }

    public PhoneListSeed(Optional<String> seedPhone, PhoneDirectory directory, Clock clock) {
        this.seedPhone = seedPhone;
        this.directory = directory;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent event) {
        seed();
    }

    public void seed() {
        seedPhone.ifPresent(phone -> {
            boolean added = directory.add(new PhoneEntry(phone, NAME, USERNAME, ROLE, clock.instant()));
            LOG.infof(added ? "Seed row added (%s)" : "Seed row already present (%s)", PhoneMask.mask(phone));
        });
    }
}
