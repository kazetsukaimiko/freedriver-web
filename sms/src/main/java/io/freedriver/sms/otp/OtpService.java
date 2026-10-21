package io.freedriver.sms.otp;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.SeedAgreement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sign-in codes for numbers on the phone list. Callers get the same answer for listed and
 * unlisted numbers; only listed numbers reach the sender.
 */
@ApplicationScoped
public class OtpService {

    private static final Logger LOG = Logger.getLogger(OtpService.class);
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    private final PhoneDirectory directory;
    private final ActiveSender sender;
    private final SeedAgreement seedAgreement;
    private final ProviderOutage outage;

    @Inject
    public OtpService(PhoneDirectory directory, ActiveSender sender, SeedAgreement seedAgreement,
                      ProviderOutage outage) {
        this.directory = directory;
        this.sender = sender;
        this.seedAgreement = seedAgreement;
        this.outage = outage;
    }

    /**
     * Texts a code when the number is on the list. Unlisted numbers get no provider call and nothing
     * is stored. On the seeded number's first sign-in, the agreement wording shown is stored first,
     * and no code is sent when it cannot be stored.
     */
    public void send(String phone, Optional<String> agreementShown) {
        if (directory.find(phone).isEmpty()) {
            LOG.debugf("No code sent to %s: not on the phone list", PhoneMask.mask(phone));
            return;
        }
        if (agreementShown.isPresent() && seedAgreement.awaited(phone)
                && !seedAgreement.record(phone, agreementShown.get())) {
            return;
        }
        sender.get().sendCode(phone);
    }

    /**
     * Texts a code to a number that already has an agreement and is not on the list yet.
     * The invite page sends the code before the number is listed.
     */
    public SendOutcome sendAgreed(String phone) {
        return sender.get().sendCode(phone);
    }

    /**
     * Checks a code for a listed number. Throws {@link CodeRejectedException} for an unlisted number
     * while the provider is answering, a malformed code, or a code the provider rejects (wrong,
     * expired or none pending), and {@link SenderUnavailableException} when the provider errors or
     * times out. An unlisted number is not sent. While the provider is down, that number waits as
     * long as the listed number's failed call and gets the same unavailable failure. An answer from
     * the provider does not add that wait.
     */
    public void verify(String phone, String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new CodeRejectedException();
        }
        if (directory.find(phone).isEmpty()) {
            if (outage.waitIfDown()) {
                throw new SenderUnavailableException();
            }
            throw new CodeRejectedException();
        }
        check(phone, code);
    }

    /**
     * Checks a code for a number that agreed on the invite page and is not on the list yet.
     * The attempt limit and the provider expiry are the same as {@link #verify}.
     */
    public void verifyAgreed(String phone, String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new CodeRejectedException();
        }
        check(phone, code);
    }

    private void check(String phone, String code) {
        long started = System.nanoTime();
        CheckOutcome outcome = sender.get().checkCode(phone, code);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        switch (outcome) {
            case APPROVED -> outage.answered();
            case WRONG_CODE -> {
                outage.answered();
                throw new CodeRejectedException();
            }
            case FAILED -> {
                outage.failed(elapsed);
                throw new SenderUnavailableException();
            }
        }
    }
}
