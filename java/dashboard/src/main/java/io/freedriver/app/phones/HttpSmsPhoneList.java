package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.app.metrics.BlockingIo;
import io.freedriver.sms.api.SmsPhonesApi;
import io.freedriver.sms.model.AttachUser;
import io.freedriver.sms.model.ListedPhone;
import io.freedriver.sms.model.RemovePhone;
import io.freedriver.sms.model.WhitelistPhone;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Calls the sms service through the sms phone-list REST client. The shared secret never goes to the browser. */
@BlockingIo
@ApplicationScoped
public class HttpSmsPhoneList implements SmsPhoneList {

    private final SmsPhonesApi phones;
    private final String secret;

    @Inject
    HttpSmsPhoneList(@RestClient SmsPhones phones,
                     @ConfigProperty(name = "sms.otp.shared-secret") Optional<String> secret) {
        this((SmsPhonesApi) phones, secret.orElse(""));
    }

    HttpSmsPhoneList(SmsPhonesApi phones, String secret) {
        this.phones = phones;
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
    public boolean whitelist(String phone, String name) {
        String listed = name == null || name.isBlank() ? null : name;
        return call(() -> phones.whitelist(new WhitelistPhone(phone, listed)).added());
    }

    @Override
    public boolean attachUsername(String phone, String username) {
        return call(() -> phones.attach(new AttachUser(phone, username)).attached());
    }

    /**
     * Refuses before any connection when the secret is unset. An argument the client's validation
     * refuses answers like a 400 from sms, which would refuse it the same way. A transport or body
     * failure is upstream.
     */
    private <T> T call(Supplier<T> request) {
        requireSecret();
        try {
            return request.get();
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (ConstraintViolationException invalid) {
            throw new SmsRefused(400);
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
