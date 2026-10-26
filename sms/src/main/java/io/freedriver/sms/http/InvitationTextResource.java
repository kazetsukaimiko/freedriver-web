package io.freedriver.sms.http;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.invite.InvitationLink;
import io.freedriver.sms.invite.InvitationSender;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.otp.SendOutcome;
import io.freedriver.sms.otp.SenderUnavailableException;
import io.freedriver.sms.security.DailySendCap;
import io.freedriver.sms.security.PhoneRateLimiter;
import io.freedriver.sms.security.RateLimitedException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Sends the one invitation text for a create. The sender is chosen with
 * {@code Instance} so {@code @LookupIfProperty} selects fake or Twilio the same way the code sender does.
 */
public class InvitationTextResource implements InvitationTextApi {

    private static final Logger LOG = Logger.getLogger(InvitationTextResource.class);

    private final InvitationSender sender;
    private final PhoneRateLimiter limiter;
    private final DailySendCap dailyCap;
    private final String publicBase;

    @Inject
    public InvitationTextResource(Instance<InvitationSender> senders, PhoneRateLimiter limiter, DailySendCap dailyCap,
                                  @ConfigProperty(name = "freedriver.public-base-url",
                                          defaultValue = "https://app.freedriver.io") String publicBase) {
        this.sender = senders.get();
        this.limiter = limiter;
        this.dailyCap = dailyCap;
        this.publicBase = publicBase;
    }

    /** Builds the sender before traffic, so a malformed From number stops startup. */
    void onStart(@Observes StartupEvent event) {
        LOG.infof("Invitation sender ready: %s", sender.getClass().getSimpleName());
    }

    @Override
    public SentResponse text(InvitationRequest request) {
        String phone = request.phone();
        if (!limiter.tryAcquireSend(phone)) {
            throw new RateLimitedException(limiter.window());
        }
        if (!sender.configured()) {
            LOG.warnf("Invitation text to %s is waiting for an invitation sender", PhoneMask.mask(phone));
            throw new SenderUnavailableException();
        }
        String body;
        try {
            body = InvitationLink.text(publicBase, phone);
        } catch (IllegalArgumentException e) {
            LOG.error("freedriver.public-base-url cannot build an invitation link");
            throw new SenderUnavailableException();
        }
        if (!dailyCap.tryAcquire()) {
            throw new SenderUnavailableException();
        }
        if (sender.send(phone, body) != SendOutcome.SENT) {
            LOG.warnf("Invitation text was not sent to %s", PhoneMask.mask(phone));
            throw new SenderUnavailableException();
        }
        LOG.infof("Invitation text sent to %s", PhoneMask.mask(phone));
        return SentResponse.invite(phone);
    }
}
