package io.freedriver.sms.twilio;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.otp.AgreementGatedSender;
import io.freedriver.sms.otp.CheckOutcome;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.otp.SendResult;
import io.freedriver.sms.otp.VerificationSid;
import io.freedriver.sms.phones.ConsentLedger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.ws.rs.ProcessingException;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Twilio Verify client. It creates a verification (Twilio generates and texts the code), checks
 * a code, and cancels a verification by setting its status to {@code canceled}. It never sends
 * its own code, never stores or logs a code, and logs failures with the number masked to its last
 * two digits. The REST client it uses has request and response logging off.
 */
public class TwilioVerifySender extends AgreementGatedSender {

    private static final Logger LOG = Logger.getLogger(TwilioVerifySender.class);
    static final String MDC_CID = "smsCid";

    private final TwilioCredentials credentials;
    private final TwilioEndpoint endpoint;
    private final String serviceSid;
    private final Duration timeout;
    private TwilioVerifyApi twilio;

    public TwilioVerifySender(TwilioCredentials credentials, TwilioEndpoint endpoint, Duration timeout,
                              ConsentLedger consents) {
        super(consents);
        this.credentials = credentials;
        this.endpoint = endpoint;
        this.serviceSid = credentials.verifyServiceSid();
        this.timeout = timeout;
    }

    @Override
    protected SendResult deliver(String phone) {
        String masked = PhoneMask.mask(phone);
        try {
            TwilioVerification created = twilio().create(serviceSid, phone, "sms");
            String sid = created == null ? null : created.sid();
            String cid = correlationId();
            if (!VerificationSid.matches(sid)) {
                LOG.warnf("Twilio accepted the code send to %s without a verification sid cid=%s", masked, cid);
                return SendResult.of(SendOutcome.SENT);
            }
            LOG.infof("Twilio accepted the code send to %s sid=%s cid=%s",
                    masked, VerificationSid.maskForLog(sid), cid);
            return new SendResult(SendOutcome.SENT, sid);
        } catch (TwilioRefused refused) {
            LOG.warnf("Twilio refused the code send to %s (HTTP %d, Twilio error %s)",
                    masked, refused.status(), refused.twilioError());
        } catch (ProcessingException failure) {
            if (timedOut(failure)) {
                LOG.warnf("Twilio timed out sending a code to %s after %s", masked, timeout);
            } else {
                LOG.warnf("Twilio code send to %s failed: %s", masked, causeName(failure));
            }
        }
        return SendResult.of(SendOutcome.FAILED);
    }

    @Override
    public boolean cancelVerification(String verificationSid) {
        if (!VerificationSid.matches(verificationSid)) {
            return false;
        }
        try {
            twilio().update(serviceSid, verificationSid, "canceled");
            return true;
        } catch (TwilioRefused refused) {
            if (refused.status() == 404) {
                return true;
            }
            LOG.warnf("Twilio did not cancel a verification (HTTP %d, Twilio error %s)",
                    refused.status(), refused.twilioError());
        } catch (ProcessingException failure) {
            if (timedOut(failure)) {
                LOG.warnf("Twilio timed out canceling a verification after %s", timeout);
            } else {
                LOG.warnf("Twilio cancel failed: %s", causeName(failure));
            }
        }
        return false;
    }

    @Override
    public CheckOutcome checkCode(String phone, String code) {
        String masked = PhoneMask.mask(phone);
        try {
            TwilioVerification checked = twilio().check(serviceSid, phone, code);
            return checked != null && "approved".equals(checked.status())
                    ? CheckOutcome.APPROVED
                    : CheckOutcome.WRONG_CODE;
        } catch (TwilioRefused refused) {
            int status = refused.status();
            if (status == 429 && "60202".equals(refused.twilioError())) {
                LOG.infof("Twilio check attempt limit reached for %s (HTTP %d, Twilio error 60202)",
                        masked, status);
                return CheckOutcome.TOO_MANY_ATTEMPTS;
            }
            if (status == 404 || status == 429) {
                // No pending verification (expired, already used) or out of check attempts.
                LOG.infof("Twilio has no open verification for %s (HTTP %d, Twilio error %s)",
                        masked, status, refused.twilioError());
                return CheckOutcome.WRONG_CODE;
            }
            LOG.warnf("Twilio refused the code check for %s (HTTP %d, Twilio error %s)",
                    masked, status, refused.twilioError());
        } catch (ProcessingException failure) {
            if (timedOut(failure)) {
                LOG.warnf("Twilio timed out checking a code for %s after %s", masked, timeout);
            } else {
                LOG.warnf("Twilio code check for %s failed: %s", masked, causeName(failure));
            }
        }
        return CheckOutcome.FAILED;
    }

    /**
     * Built on first use from the checked endpoint, so the production URL guard in
     * {@link TwilioEndpoint} still decides where requests go. Redirects are not followed.
     */
    private synchronized TwilioVerifyApi twilio() {
        if (twilio == null) {
            long millis = timeout.toMillis();
            twilio = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(endpoint.base())
                    .connectTimeout(millis, TimeUnit.MILLISECONDS)
                    .readTimeout(millis, TimeUnit.MILLISECONDS)
                    .followRedirects(false)
                    .register(new TwilioBasicAuth(credentials))
                    .register(new TwilioRefused.Mapper())
                    .build(TwilioVerifyApi.class);
        }
        return twilio;
    }

    private static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }

    /** The class of the underlying failure. Its message can carry the URL, so it is not logged. */
    private static String causeName(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName();
    }

    private static String correlationId() {
        Object value = MDC.get(MDC_CID);
        return value == null || value.toString().isBlank() ? "-" : value.toString();
    }
}
