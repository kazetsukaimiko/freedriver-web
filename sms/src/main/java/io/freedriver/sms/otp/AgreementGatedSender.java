package io.freedriver.sms.otp;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import org.jboss.logging.Logger;

/**
 * Base of every sender. The agreement check runs here, right before the provider call, so no
 * code path can text a number that has no recorded sign-in agreement.
 */
public abstract class AgreementGatedSender implements SmsSender {

    private static final Logger LOG = Logger.getLogger(AgreementGatedSender.class);

    private final ConsentLedger consents;

    protected AgreementGatedSender(ConsentLedger consents) {
        this.consents = consents;
    }

    @Override
    public final SendOutcome sendCode(String phone) {
        if (!consents.hasAgreed(phone, ConsentPurpose.SIGN_IN_CODES)) {
            LOG.warnf("Code send refused for %s: no recorded sign-in agreement", PhoneMask.mask(phone));
            return SendOutcome.REFUSED_NO_AGREEMENT;
        }
        return deliver(phone);
    }

    /** Provider call for a number whose agreement is on record. */
    protected abstract SendOutcome deliver(String phone);
}
