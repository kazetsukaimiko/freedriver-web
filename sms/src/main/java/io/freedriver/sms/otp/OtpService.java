package io.freedriver.sms.otp;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.SmsConfig;
import io.freedriver.sms.model.VerifyFailure;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.SeedAgreement;
import io.freedriver.sms.phones.SignInTimes;
import io.freedriver.sms.security.PhoneRateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sign-in codes for numbers on the phone list. Callers get the same answer for listed and
 * unlisted numbers; only a number with an agreement reaches the sender.
 */
@ApplicationScoped
public class OtpService {

    private static final Logger LOG = Logger.getLogger(OtpService.class);
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");

    private final PhoneDirectory directory;
    private final ActiveSender sender;
    private final SeedAgreement seedAgreement;
    private final ProviderOutage outage;
    private final VerificationLedger ledger;
    private final PhoneRateLimiter limiter;
    private final SignInTimes signIns;
    private final Duration retryAfter;

    @Inject
    public OtpService(PhoneDirectory directory, ActiveSender sender, SeedAgreement seedAgreement,
                      ProviderOutage outage, VerificationLedger ledger, PhoneRateLimiter limiter,
                      SignInTimes signIns, SmsConfig config) {
        this.directory = directory;
        this.sender = sender;
        this.seedAgreement = seedAgreement;
        this.outage = outage;
        this.ledger = ledger;
        this.limiter = limiter;
        this.signIns = signIns;
        this.retryAfter = config.rateLimit().window();
    }

    /**
     * Texts a code when the number is on the list. An unlisted number gets no provider call and no
     * phone-list or agreement record. The send time is recorded either way. When a listed number
     * has no agreement yet, the wording shown is stored first, and no code is sent when it
     * cannot be stored.
     */
    public void send(String phone, Optional<String> agreementShown) {
        if (directory.find(phone).isEmpty()) {
            LOG.debugf("No code sent to %s: not on the phone list", PhoneMask.mask(phone));
            replaceVerification(phone, false);
            return;
        }
        if (agreementShown.isPresent() && seedAgreement.awaited(phone)
                && !seedAgreement.record(phone, agreementShown.get())) {
            return;
        }
        replaceVerification(phone, true);
    }

    /**
     * Texts a code to a number that already has an agreement and is not on the list yet.
     * The invite page sends the code before the number is listed.
     */
    public SendOutcome sendAgreed(String phone) {
        return replaceVerification(phone, true);
    }

    /**
     * Checks a code. A missing, cancelled, replaced, or more-than-5-minute-old verification is
     * expired. The check after the fifth attempt throws {@link TooManyAttemptsException}. Any
     * other miss throws {@link CodeRejectedException}. While the provider is down, an unlisted
     * number inside the window waits as long as the listed number's failed call and gets the same
     * unavailable failure.
     */
    public void verify(String phone, String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new CodeRejectedException();
        }
        rejectIfExpired(phone);
        if (directory.find(phone).isEmpty()) {
            if (outage.waitIfDown()) {
                throw new SenderUnavailableException();
            }
            throw new CodeRejectedException(VerifyFailure.WRONG_CODE);
        }
        check(phone, code);
    }

    /**
     * Checks a code for a number that agreed on the invite page and is not on the list yet.
     * The attempt limit and the 5-minute expiry are the same as {@link #verify}.
     */
    public void verifyAgreed(String phone, String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new CodeRejectedException();
        }
        rejectIfExpired(phone);
        check(phone, code);
    }

    /**
     * A new send replaces the open verification. The previous Twilio verification is canceled,
     * and the attempt count for this number starts over.
     */
    private SendOutcome replaceVerification(String phone, boolean deliver) {
        String previous = ledger.replace(phone);
        if (previous != null) {
            sender.get().cancelVerification(previous);
        }
        limiter.clearWrongCodes(phone);
        if (!deliver) {
            return SendOutcome.SENT;
        }
        SendResult result = sender.get().sendCode(phone);
        if (result.outcome() == SendOutcome.SENT) {
            ledger.rememberSid(phone, result.verificationSid());
        }
        return result.outcome();
    }

    private void rejectIfExpired(String phone) {
        if (!ledger.expired(phone)) {
            return;
        }
        String sid = ledger.peekExpiredSid(phone);
        if (sid != null && sender.get().cancelVerification(sid)) {
            ledger.dropSid(phone);
        }
        throw new CodeRejectedException(VerifyFailure.EXPIRED);
    }

    private void check(String phone, String code) {
        long started = System.nanoTime();
        CheckOutcome outcome = sender.get().checkCode(phone, code);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        switch (outcome) {
            case APPROVED -> {
                ledger.clear(phone);
                outage.answered();
                // The accepted code is the sign-in the phones page can see. A missed write leaves the login link up.
                if (directory.find(phone).isPresent()) {
                    try {
                        signIns.record(phone);
                    } catch (RuntimeException e) {
                        LOG.warnf("Sign-in time was not stored for %s", PhoneMask.mask(phone));
                    }
                }
            }
            case WRONG_CODE -> {
                outage.answered();
                throw new CodeRejectedException(VerifyFailure.WRONG_CODE);
            }
            case TOO_MANY_ATTEMPTS -> {
                ledger.exhaust(phone);
                outage.answered();
                throw new TooManyAttemptsException(retryAfter);
            }
            case FAILED -> {
                outage.failed(elapsed);
                throw new SenderUnavailableException();
            }
        }
    }
}
