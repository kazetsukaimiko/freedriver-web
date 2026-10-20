package io.freedriver.sms.otp;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.SeedAgreement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    @Inject
    public OtpService(PhoneDirectory directory, ActiveSender sender, SeedAgreement seedAgreement) {
        this.directory = directory;
        this.sender = sender;
        this.seedAgreement = seedAgreement;
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
     * Checks a code for a listed number. Throws {@link CodeRejectedException} for an unlisted number,
     * a malformed code, or a code the provider rejects (wrong, expired or none pending), and
     * {@link SenderUnavailableException} when the provider errors or times out.
     */
    public void verify(String phone, String code) {
        if (directory.find(phone).isEmpty() || !CODE.matcher(code).matches()) {
            throw new CodeRejectedException();
        }
        switch (sender.get().checkCode(phone, code)) {
            case APPROVED -> {
            }
            case WRONG_CODE -> throw new CodeRejectedException();
            case FAILED -> throw new SenderUnavailableException();
        }
    }
}
