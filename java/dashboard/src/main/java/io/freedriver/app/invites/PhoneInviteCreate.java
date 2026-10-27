package io.freedriver.app.invites;

import io.freedriver.app.phones.PhoneAlreadyExists;
import io.freedriver.app.phones.PhoneSignInDirectory;
import io.freedriver.app.phones.SmsPhoneList;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.regex.Pattern;

/**
 * Invite create for a phone number. The number is listed, then the Keycloak user is created.
 * A second user for that phone is refused. No invite link is minted.
 */
@ApplicationScoped
public class PhoneInviteCreate {

    private static final Logger LOG = Logger.getLogger(PhoneInviteCreate.class);
    private static final Pattern PHONE = Pattern.compile("^\\+1[0-9]{10}$");

    private final SmsPhoneList sms;
    private final PhoneSignInDirectory keycloak;

    @Inject
    public PhoneInviteCreate(SmsPhoneList sms, PhoneSignInDirectory keycloak) {
        this.sms = sms;
        this.keycloak = keycloak;
    }

    public Result create(String phone, String name) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            return new Result.Rejected(400);
        }
        String label = name == null ? "" : name.strip();
        if (label.length() > 40) {
            return new Result.Rejected(400);
        }
        boolean added;
        try {
            if (sms.list().stream().anyMatch(row -> phone.equals(row.phone()))) {
                return new Result.Rejected(409);
            }
            added = sms.whitelist(phone, label.isEmpty() ? null : label);
        } catch (RuntimeException e) {
            LOG.warnf("Invite list add failed for %s", PhoneLog.mask(phone));
            return new Result.Rejected(502);
        }
        if (!added) {
            return new Result.Rejected(409);
        }
        PhoneSignInDirectory.Created user;
        try {
            user = keycloak.createPhoneSignInUser(phone);
        } catch (PhoneAlreadyExists e) {
            drop(phone);
            return new Result.Rejected(409);
        } catch (RuntimeException e) {
            drop(phone);
            LOG.warnf("Invite account was not created for %s", PhoneLog.mask(phone));
            return new Result.Rejected(502);
        }
        try {
            if (!sms.attachUsername(phone, user.username())) {
                LOG.warnf("Invite username was not stored for %s", PhoneLog.mask(phone));
                return new Result.Rejected(502);
            }
        } catch (RuntimeException e) {
            LOG.warnf("Invite username was not stored for %s", PhoneLog.mask(phone));
            return new Result.Rejected(502);
        }
        LOG.infof("Invite listed %s", PhoneLog.mask(phone));
        return new Result.Listed(PhoneLog.mask(phone));
    }

    private void drop(String phone) {
        try {
            sms.remove(phone);
        } catch (RuntimeException e) {
            LOG.warnf("Invite list rollback failed for %s", PhoneLog.mask(phone));
        }
    }

    public sealed interface Result {
        record Listed(String maskedPhone) implements Result {
        }

        record Rejected(int status) implements Result {
        }
    }
}
