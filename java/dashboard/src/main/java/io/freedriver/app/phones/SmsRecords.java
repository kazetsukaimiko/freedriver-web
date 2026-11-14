package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.sms.model.PhoneRecord;
import io.freedriver.sms.model.SignInRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.function.Supplier;

/**
 * The stored phone and sign-in rows, read from sms, which owns them. A refusal or a
 * transport failure is {@link InviteUpstreamException} (502). No JAX-RS type leaves this class.
 */
@ApplicationScoped
public class SmsRecords {

    private final SmsPhones phones;
    private final SmsSignIns signIns;

    @Inject
    SmsRecords(@RestClient SmsPhones phones, @RestClient SmsSignIns signIns) {
        this.phones = phones;
        this.signIns = signIns;
    }

    public List<PhoneRecord> phones() {
        return call(() -> phones.records().phones());
    }

    public List<SignInRecord> signIns() {
        return call(() -> signIns.list().signIns());
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InviteUpstreamException("sms records failed", e);
        }
    }
}
