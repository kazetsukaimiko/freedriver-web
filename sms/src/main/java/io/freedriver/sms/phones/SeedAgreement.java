package io.freedriver.sms.phones;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.SmsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Optional;

/**
 * Sign-in agreement for a listed number that has none yet. The login form shows the wording
 * and Continue stores it before the code is sent. An unlisted number is not recorded here.
 */
@ApplicationScoped
public class SeedAgreement {

    private static final Logger LOG = Logger.getLogger(SeedAgreement.class);

    private final Optional<String> seedPhone;
    private final PhoneDirectory directory;
    private final ConsentLedger consents;
    private final Clock clock;

    @Inject
    public SeedAgreement(SmsConfig config, PhoneDirectory directory, ConsentLedger consents) {
        this(config.seedPhone(), directory, consents, Clock.systemUTC());
    }

    public SeedAgreement(Optional<String> seedPhone, PhoneDirectory directory, ConsentLedger consents, Clock clock) {
        this.seedPhone = seedPhone;
        this.directory = directory;
        this.consents = consents;
        this.clock = clock;
    }

    /** True while {@code phone} is on the list and has no sign-in agreement on record. */
    public boolean awaited(String phone) {
        return directory.find(phone).isPresent()
                && !consents.hasAgreed(phone, ConsentPurpose.SIGN_IN_CODES);
    }

    /** Stores the agreement with the wording shown; false, logged with the number masked, when it could not be stored. */
    public boolean record(String phone, String wording) {
        ConsentSource source = seedPhone.filter(phone::equals).isPresent()
                ? ConsentSource.SEEDED_FIRST_SIGN_IN
                : ConsentSource.SIGN_IN_FORM;
        try {
            consents.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, wording,
                    source, clock.instant()));
            return true;
        } catch (UncheckedIOException e) {
            LOG.errorf("Sign-in agreement for %s not stored, so no code was sent: %s", PhoneMask.mask(phone),
                    e.getClass().getSimpleName());
            return false;
        }
    }
}
