package io.freedriver.sms.api;

import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Phone list for the portal. sms does not call Keycloak. Callers still present the shared secret.
 */
@Path("/sms/phones")
@Produces(MediaType.APPLICATION_JSON)
public class PhoneListResource {

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;

    @GET
    public PhoneList list() {
        List<ListedPhone> phones = directory.list().stream()
                .sorted(Comparator.comparing(PhoneEntry::addedAt))
                .map(entry -> new ListedPhone(
                        entry.phone(),
                        entry.name(),
                        entry.role(),
                        entry.addedAt(),
                        consentedAt(entry.phone()),
                        null))
                .toList();
        return new PhoneList(phones);
    }

    @POST
    @Path("remove")
    @Consumes(MediaType.APPLICATION_JSON)
    public Removed remove(@Valid @NotNull RemovePhone request) {
        return new Removed(directory.remove(request.phone()));
    }

    private Instant consentedAt(String phone) {
        return consents.history(phone).stream()
                .filter(record -> record.purpose() == ConsentPurpose.SIGN_IN_CODES)
                .map(ConsentRecord::givenAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    public record PhoneList(List<ListedPhone> phones) {
    }

    public record ListedPhone(
            String phone,
            String name,
            String role,
            Instant addedAt,
            Instant consentedAt,
            Instant lastSignIn) {
    }

    public record RemovePhone(@UsPhoneNumber String phone) {
    }

    public record Removed(boolean removed) {
    }
}
