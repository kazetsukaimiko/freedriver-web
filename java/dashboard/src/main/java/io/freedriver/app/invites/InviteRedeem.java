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

    public Outcome start(String code, String rawPhone, String wording) {
        if (!allowed(code)) {
            return Outcome.limited();
        }
        if (!InviteWording.TEXT.equals(wording)) {
            return Outcome.failed(Failure.BAD_REQUEST);
        }
        Optional<InviteStore.InviteState> found = store.state(code);
        if (found.isEmpty() || !found.get().open() || found.get().submissions() >= 3) {
            return Outcome.dead();
        }
        if (found.get().codesSent() >= 3) {
            return Outcome.codes();
        }
        Optional<String> phone = UsPhones.canonical(rawPhone);
        if (phone.isEmpty()) {
            return Outcome.failed(Failure.BAD_PHONE);
        }
        if (listed(phone.get())) {
            return Outcome.listed();
        }
        InviteStore.InviteState previous = found.get();
        InviteStore.Slot slot = store.takeSubmission(code, phone.get());
        if (slot == InviteStore.Slot.CODES) {
            return Outcome.codes();
        }
        if (slot != InviteStore.Slot.OK) {
            return Outcome.dead();
        }
        return sent(code, previous, phone.get(), true);
    }

    public Outcome resend(String code) {
        if (!allowed(code)) {
            return Outcome.limited();
        }
        Optional<InviteStore.InviteState> found = store.state(code);
        if (found.isEmpty() || !found.get().open() || found.get().pendingPhone() == null) {
            return Outcome.dead();
        }
        if (found.get().codesSent() >= 3) {
            return Outcome.codes();
        }
        InviteStore.InviteState previous = found.get();
        InviteStore.Slot slot = store.takeResend(code);
        if (slot == InviteStore.Slot.CODES) {
            return Outcome.codes();
        }
        if (slot != InviteStore.Slot.OK) {
            return Outcome.dead();
        }
        return sent(code, previous, previous.pendingPhone(), false);
    }

    public Outcome verify(String code, String otp) {
        if (!allowed(code)) {
            return Outcome.limited();
        }
        Optional<InviteStore.InviteState> found = store.state(code);
        if (found.isEmpty() || !found.get().open() || found.get().pendingPhone() == null) {
            return Outcome.dead();
        }
        String phone = found.get().pendingPhone();
        SmsPhoneList.InviteCheck check;
        try {
            check = sms.checkInviteCode(phone, otp);
        } catch (InviteUpstreamException e) {
            LOG.warnf("Invite check failed for %s", PhoneLog.mask(phone));
            return Outcome.unavailable();
        }
        switch (check) {
            case WRONG -> {
                return Outcome.failed(Failure.INVALID_CODE);
            }
            case LIMITED -> {
                return Outcome.limited();
            }
            case UNAVAILABLE -> {
                return Outcome.unavailable();
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
            return Outcome.unavailable();
        }
        boolean added;
        try {
            added = sms.addInvited(phone, found.get().name(), user.username());
        } catch (InviteUpstreamException e) {
            LOG.warnf("Invite list add failed for %s", PhoneLog.mask(phone));
            return Outcome.unavailable();
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
            return Outcome.dead();
        }
        LOG.infof("Invite completed for %s", PhoneLog.mask(phone));
        return Outcome.complete();
    }

    private Outcome sent(String code, InviteStore.InviteState previous, String phone, boolean submission) {
        SmsPhoneList.InviteText sent;
        try {
            sent = sms.sendInviteCode(phone, previous.id());
        } catch (InviteUpstreamException e) {
            store.restore(code, previous);
            LOG.warnf("Invite text failed for %s", PhoneLog.mask(phone));
            return Outcome.unavailable();
        }
        if (sent != SmsPhoneList.InviteText.SENT) {
            store.restore(code, previous);
            return switch (sent) {
                case ALREADY_LISTED -> Outcome.listed();
                case LIMITED -> Outcome.limited();
                case SENT, UNAVAILABLE -> Outcome.unavailable();
            };
        }
        LOG.infof(submission ? "Invite code requested for %s" : "Invite code resent for %s", PhoneLog.mask(phone));
        int codes = store.state(code).map(InviteStore.InviteState::codesSent).orElse(previous.codesSent() + 1);
        return Outcome.ok(codes);
    }

    private boolean listed(String phone) {
        return sms.list().stream().anyMatch(row -> phone.equals(row.phone()));
    }

    private boolean allowed(String code) {
        return limiter.tryAcquire(InviteStore.rateKey(code));
    }

    /** Why a redeem step stopped. The HTTP resource maps these to status codes. */
    public enum Failure {
        BAD_REQUEST, BAD_PHONE, DEAD, ALREADY_LISTED, CODES, LIMITED, UNAVAILABLE, INVALID_CODE
    }

    public record Outcome(Failure failure, Integer codesSent, boolean signedIn) {

        static Outcome ok(int codesSent) {
            return new Outcome(null, codesSent, false);
        }

        static Outcome complete() {
            return new Outcome(null, null, true);
        }

        static Outcome dead() {
            return failed(Failure.DEAD);
        }

        static Outcome listed() {
            return failed(Failure.ALREADY_LISTED);
        }

        static Outcome codes() {
            return failed(Failure.CODES);
        }

        static Outcome limited() {
            return failed(Failure.LIMITED);
        }

        static Outcome unavailable() {
            return failed(Failure.UNAVAILABLE);
        }

        static Outcome failed(Failure failure) {
            return new Outcome(failure, null, false);
        }
    }
}
