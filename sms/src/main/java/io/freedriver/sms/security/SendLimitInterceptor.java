package io.freedriver.sms.security;

import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.SeedAgreement;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * The send limits on a method marked {@code @PhoneRateLimited(SEND)}, whose argument is the
 * validated {@link OtpSendRequest}. First a per-number window, past which the call throws
 * {@link RateLimitedException}. Then the service-wide daily cap, which counts only sends that reach
 * the provider (listed numbers with an agreement on file, or a listed number's first sign-in
 * carrying its agreement). Once the cap is reached, those sends get the same {@code sent} answer
 * every other number gets, and the method does not run, so there is no provider call.
 */
@PhoneRateLimited(PhoneRateLimited.Kind.SEND)
@Interceptor
@Priority(PhoneRateLimited.PRIORITY)
public class SendLimitInterceptor {

    private final PhoneRateLimiter limiter;
    private final DailySendCap dailyCap;
    private final PhoneDirectory directory;
    private final ConsentLedger consents;
    private final SeedAgreement seedAgreement;

    @Inject
    public SendLimitInterceptor(PhoneRateLimiter limiter, DailySendCap dailyCap, PhoneDirectory directory,
                                ConsentLedger consents, SeedAgreement seedAgreement) {
        this.limiter = limiter;
        this.dailyCap = dailyCap;
        this.directory = directory;
        this.consents = consents;
        this.seedAgreement = seedAgreement;
    }

    @AroundInvoke
    Object limit(InvocationContext context) throws Exception {
        Object[] arguments = context.getParameters();
        if (arguments.length != 1 || !(arguments[0] instanceof OtpSendRequest send)) {
            return context.proceed();
        }
        String phone = send.phone();
        if (!limiter.tryAcquireSend(phone)) {
            throw new RateLimitedException(limiter.window());
        }
        if (reachesProvider(send) && !dailyCap.tryAcquire()) {
            // The cap answers exactly like a send to an unlisted number: sent, and no provider call.
            return SentResponse.otp(phone);
        }
        return context.proceed();
    }

    /**
     * Whether this send would reach the provider: the number is on the phone list and has a
     * sign-in agreement on file, or it is a listed number's first sign-in and the request carries
     * the agreement. Only those sends count against the daily cap. This decides counting only; the
     * sender's own agreement check still guards the provider call.
     */
    private boolean reachesProvider(OtpSendRequest send) {
        String phone = send.phone();
        return directory.find(phone).isPresent()
                && (consents.hasAgreed(phone, ConsentPurpose.SIGN_IN_CODES)
                || send.agreement() != null && seedAgreement.awaited(phone));
    }
}
