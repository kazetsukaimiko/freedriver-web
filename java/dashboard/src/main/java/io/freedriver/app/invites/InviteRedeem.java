package io.freedriver.app.invites;

import io.freedriver.app.appliances.CommandRateLimiter;
import io.freedriver.app.phones.PhoneSignInDirectory;
import io.freedriver.app.phones.SmsPhoneList;
import io.freedriver.app.phones.UsPhones;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The person-facing invite. Confirm stores the agreement and sends one code. A correct code uses
 * the invite, lists the number, and creates the Keycloak user. This class does not issue a session.
 */
@ApplicationScoped
public class InviteRedeem {

    private static final Logger LOG = Logger.getLogger(InviteRedeem.class);

    private final InviteStore store;
    private final SmsPhoneList sms;
    private final PhoneSignInDirectory keycloak;
    private final CommandRateLimiter limiter;

    @Inject
    public InviteRedeem(InviteStore store, SmsPhoneList sms, PhoneSignInDirectory keycloak, CommandRateLimiter limiter) {
        this.store = store;
        this.sms = sms;
        this.keycloak = keycloak;
        this.limiter = limiter;
    }

    public Answer start(String code, String rawPhone, String wording) {
        if (!allowed(code)) {
            return Answer.limited();
        }
        if (!InviteWording.TEXT.equals(wording)) {
            return Answer.of(400, "bad-request");
        }
        Optional<InviteStore.InviteState> found = store.state(code);
        if (found.isEmpty() || !found.get().open() || found.get().submissions() >= 3) {
            return Answer.dead();
        }
        if (found.get().codesSent() >= 3) {
            return Answer.codes();
        }
        Optional<String> phone = UsPhones.canonical(rawPhone);
        if (phone.isEmpty()) {
            return Answer.of(400, "bad-phone");
        }
        if (listed(phone.get())) {
            return Answer.listed();
        }
        InviteStore.InviteState previous = found.get();
        InviteStore.Slot slot = store.takeSubmission(code, phone.get());
        if (slot == InviteStore.Slot.CODES) {
            return Answer.codes();
        }
        if (slot != InviteStore.Slot.OK) {
            return Answer.dead();
        }
        return sent(code, previous, phone.get(), true);
    }

    public Answer resend(String code) {
        if (!allowed(code)) {
            return Answer.limited();
        }
        Optional<InviteStore.InviteState> found = store.state(code);
        if (found.isEmpty() || !found.get().open() || found.get().pendingPhone() == null) {
            return Answer.dead();
        }
        if (found.get().codesSent() >= 3) {
            return Answer.codes();
        }
        InviteStore.InviteState previous = found.get();
        InviteStore.Slot slot = store.takeResend(code);
        if (slot == InviteStore.Slot.CODES) {
            return Answer.codes();
        }
        if (slot != InviteStore.Slot.OK) {
            return Answer.dead();
        }
        return sent(code, previous, previous.pendingPhone(), false);
    }

    public Answer verify(String code, String otp) {
        if (!allowed(code)) {
            return Answer.limited();
        }
        Optional<InviteStore.InviteState> found = store.state(code);
        if (found.isEmpty() || !found.get().open() || found.get().pendingPhone() == null) {
            return Answer.dead();
        }
        String phone = found.get().pendingPhone();
        SmsPhoneList.InviteCheck check;
        try {
            check = sms.checkInviteCode(phone, otp);
        } catch (InviteUpstreamException e) {
            LOG.warnf("Invite check failed for %s", PhoneLog.mask(phone));
            return Answer.unavailable();
        }
        switch (check) {
            case WRONG -> {
                return Answer.of(400, "invalid-code");
            }
            case LIMITED -> {
                return Answer.limited();
            }
            case UNAVAILABLE -> {
                return Answer.unavailable();
            }
            case APPROVED -> {
                // The code matched the number this invite texted.
            }
        }
        PhoneSignInDirectory.Created user;
        try {
            user = keycloak.createPhoneSignInUser(phone);
        } catch (InviteUpstreamException e) {
            LOG.warnf("Invite account was not created for %s", PhoneLog.mask(phone));
            return Answer.unavailable();
        }
        boolean added;
        try {
            added = sms.addInvited(phone, found.get().name(), user.username());
        } catch (InviteUpstreamException e) {
            LOG.warnf("Invite list add failed for %s", PhoneLog.mask(phone));
            return Answer.unavailable();
        }
        if (store.use(code) != InviteStore.UseResult.USED) {
            if (added) {
                try {
                    sms.remove(phone);
                } catch (RuntimeException e) {
                    LOG.warnf("Invite list rollback failed for %s", PhoneLog.mask(phone));
                }
                try {
                    keycloak.removeFromPhoneSignIn(phone);
                } catch (RuntimeException e) {
                    LOG.warnf("Invite group rollback failed for %s", PhoneLog.mask(phone));
                }
            }
            return Answer.dead();
        }
        LOG.infof("Invite completed for %s", PhoneLog.mask(phone));
        return Answer.signedIn();
    }

    private Answer sent(String code, InviteStore.InviteState previous, String phone, boolean submission) {
        SmsPhoneList.InviteText sent;
        try {
            sent = sms.sendInviteCode(phone, previous.id());
        } catch (InviteUpstreamException e) {
            store.restore(code, previous);
            LOG.warnf("Invite text failed for %s", PhoneLog.mask(phone));
            return Answer.unavailable();
        }
        if (sent != SmsPhoneList.InviteText.SENT) {
            store.restore(code, previous);
            return switch (sent) {
                case ALREADY_LISTED -> Answer.listed();
                case LIMITED -> Answer.limited();
                case SENT, UNAVAILABLE -> Answer.unavailable();
            };
        }
        LOG.infof(submission ? "Invite code requested for %s" : "Invite code resent for %s", PhoneLog.mask(phone));
        int codes = store.state(code).map(InviteStore.InviteState::codesSent).orElse(previous.codesSent() + 1);
        return Answer.ok(codes);
    }

    private boolean listed(String phone) {
        return sms.list().stream().anyMatch(row -> phone.equals(row.phone()));
    }

    private boolean allowed(String code) {
        return limiter.tryAcquire(InviteStore.rateKey(code));
    }

    public record Answer(int status, String error, Integer codesSent, boolean verified) {

        static Answer ok(int codesSent) {
            return new Answer(200, null, codesSent, false);
        }

        static Answer signedIn() {
            return new Answer(200, null, null, true);
        }

        static Answer dead() {
            return of(409, "dead");
        }

        static Answer listed() {
            return of(409, "already-listed");
        }

        static Answer codes() {
            return of(409, "codes");
        }

        static Answer limited() {
            return of(429, "rate-limited");
        }

        static Answer unavailable() {
            return of(502, "unavailable");
        }

        static Answer of(int status, String error) {
            return new Answer(status, error, null, false);
        }
    }
}
