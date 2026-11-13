package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.app.invites.InviteWording;
import io.freedriver.app.metrics.BlockingIo;
import io.freedriver.sms.api.SmsInviteApi;
import io.freedriver.sms.api.SmsPhonesApi;
import io.freedriver.sms.model.AddPhone;
import io.freedriver.sms.model.AttachUser;
import io.freedriver.sms.model.InviteSend;
import io.freedriver.sms.model.ListedPhone;
import io.freedriver.sms.model.RemovePhone;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.model.WhitelistPhone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Calls the sms service through the two sms REST clients. The shared secret never goes to the browser. */
@BlockingIo
@ApplicationScoped
public class HttpSmsPhoneList implements SmsPhoneList {

    private final SmsPhonesApi phones;
    private final SmsInviteApi invites;
    private final String secret;

    @Inject
    HttpSmsPhoneList(@RestClient SmsPhones phones,
                     @RestClient SmsInvite invites,
                     @ConfigProperty(name = "sms.otp.shared-secret") Optional<String> secret) {
        this((SmsPhonesApi) phones, invites, secret.orElse(""));
    }

    HttpSmsPhoneList(SmsPhonesApi phones, SmsInviteApi invites, String secret) {
        this.phones = phones;
        this.invites = invites;
        this.secret = secret == null ? "" : secret;
    }

    @Override
    public List<PhoneRow> list() {
        return call(() -> phones.list().phones().stream().map(HttpSmsPhoneList::row).toList());
    }

    @Override
    public boolean remove(String phone) {
        return call(() -> phones.remove(new RemovePhone(phone)).removed());
    }

    @Override
    public void requireSharedSecret() {
        requireSecret();
    }

    @Override
    public InviteText sendInviteCode(String phone, UUID inviteId) {
        try {
            call(() -> invites.send(new InviteSend(phone, InviteWording.TEXT, inviteId.toString())));
            return InviteText.SENT;
        } catch (SmsRefused refused) {
            return switch (refused.status()) {
                case 409 -> InviteText.ALREADY_LISTED;
                case 429 -> InviteText.LIMITED;
                default -> InviteText.UNAVAILABLE;
            };
        }
    }

    @Override
    public InviteCheck checkInviteCode(String phone, String code) {
        try {
            call(() -> invites.verify(new VerifyRequest(phone, code)));
            return InviteCheck.APPROVED;
        } catch (SmsRefused refused) {
            return switch (refused.status()) {
                case 400 -> InviteCheck.WRONG;
                case 429 -> InviteCheck.LIMITED;
                default -> InviteCheck.UNAVAILABLE;
            };
        }
    }

    @Override
    public boolean addInvited(String phone, String name, String username) {
        return call(() -> phones.add(new AddPhone(phone, name, username, "dashboard")).added());
    }

    @Override
    public boolean whitelist(String phone, String name) {
        String listed = name == null || name.isBlank() ? null : name;
        return call(() -> phones.whitelist(new WhitelistPhone(phone, listed)).added());
    }

    @Override
    public boolean attachUsername(String phone, String username) {
        return call(() -> phones.attach(new AttachUser(phone, username)).attached());
    }

    /** Refuses before any connection when the secret is unset. A transport or body failure is upstream. */
    private <T> T call(Supplier<T> request) {
        requireSecret();
        try {
            return request.get();
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InviteUpstreamException("sms phone list failed", e);
        }
    }

    private void requireSecret() {
        if (secret.isBlank()) {
            throw new InviteUpstreamException("sms shared secret is unset");
        }
    }

    private static PhoneRow row(ListedPhone phone) {
        return new PhoneRow(phone.phone(), phone.name(), phone.role(), phone.addedAt(), phone.consentedAt(),
                phone.lastSignIn());
    }
}
