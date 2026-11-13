package io.freedriver.sms.http;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.api.SmsInviteApi;
import io.freedriver.sms.model.ErrorResponse;
import io.freedriver.sms.model.InviteSend;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.otp.OtpService;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.otp.SenderUnavailableException;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.ConsentSource;
import io.freedriver.sms.phones.InviteWording;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.security.RateLimitedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.UUID;

/**
 * Invite-page codes. The number is not on the list yet. The agreement is stored first, then one
 * code is sent. Verify uses the same attempt limit and provider expiry as sign-in.
 */
public class InviteCodeResource implements SmsInviteApi {

    private static final Logger LOG = Logger.getLogger(InviteCodeResource.class);

    private final PhoneDirectory directory;
    private final ConsentLedger consents;
    private final OtpService otp;
    private final PhoneRateLimiter limiter;
    private final DailySendCap dailyCap;
    private final Clock clock;

    @Inject
    public InviteCodeResource(PhoneDirectory directory, ConsentLedger consents, OtpService otp,
                              PhoneRateLimiter limiter, DailySendCap dailyCap) {
        this(directory, consents, otp, limiter, dailyCap, Clock.systemUTC());
    }

    InviteCodeResource(PhoneDirectory directory, ConsentLedger consents, OtpService otp,
                       PhoneRateLimiter limiter, DailySendCap dailyCap, Clock clock) {
        this.directory = directory;
        this.consents = consents;
        this.otp = otp;
        this.limiter = limiter;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    @Override
    public SentResponse send(InviteSend request) {
        if (!InviteWording.TEXT.equals(request.wording())) {
            throw refused(400, ErrorResponse.BAD_REQUEST);
        }
        UUID inviteId;
        try {
            inviteId = UUID.fromString(request.inviteId());
        } catch (IllegalArgumentException e) {
            throw refused(400, ErrorResponse.BAD_REQUEST);
        }
        if (directory.find(request.phone()).isPresent()) {
            throw refused(409, new ErrorResponse("already-listed"));
        }
        if (!limiter.tryAcquireSend(request.phone())) {
            throw new RateLimitedException(limiter.window());
        }
        consents.record(new ConsentRecord(request.phone(), ConsentPurpose.SIGN_IN_CODES, InviteWording.TEXT,
                ConsentSource.INVITE_PAGE, clock.instant(), inviteId.toString()));
        LOG.infof("Invite agreement stored for %s", PhoneMask.mask(request.phone()));
        if (!dailyCap.tryAcquire()) {
            throw new SenderUnavailableException();
        }
        SendOutcome outcome = otp.sendAgreed(request.phone());
        if (outcome != SendOutcome.SENT) {
            LOG.warnf("Invite code was not sent to %s", PhoneMask.mask(request.phone()));
            throw new SenderUnavailableException();
        }
        return SentResponse.otp(request.phone());
    }

    private static WebApplicationException refused(int status, ErrorResponse body) {
        return new WebApplicationException(Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build());
    }

    @Override
    public VerifiedResponse verify(VerifyRequest request) {
        otp.verifyAgreed(request.phone(), request.code());
        return VerifiedResponse.otp(request.phone());
    }
}
