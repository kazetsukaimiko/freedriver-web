package io.freedriver.sms.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.phones.SignInTimes;
import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @Inject
    SignInTimes signIns;

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
                        signIns.last(entry.phone())))
                .toList();
        return new PhoneList(phones);
    }

    @POST
    @Path("remove")
    @Consumes(MediaType.APPLICATION_JSON)
    public Removed remove(@Valid @NotNull RemovePhone request) {
        boolean removed = directory.remove(request.phone());
        if (removed) {
            signIns.forget(request.phone());
        }
        return new Removed(removed);
    }

    /** Puts an invited number on the list after its code is verified. Role is {@code dashboard} only. */
    @POST
    @Path("add")
    @Consumes(MediaType.APPLICATION_JSON)
    public Added add(@Valid @NotNull AddPhone request) {
        boolean added = directory.add(new PhoneEntry(
                request.phone(), request.name(), request.username(), request.role(), Instant.now()));
        return new Added(added);
    }

    /** Lists a number before its Keycloak user exists. The role is always {@code dashboard}. */
    @POST
    @Path("whitelist")
    @Consumes(MediaType.APPLICATION_JSON)
    public Added whitelist(@Valid @NotNull WhitelistPhone request) {
        String name = request.name() == null ? null : request.name().strip();
        boolean added = directory.whitelist(request.phone(), name == null || name.isEmpty() ? null : name);
        return new Added(added);
    }

    /** Records the username of the user created for a listed number. */
    @POST
    @Path("attach")
    @Consumes(MediaType.APPLICATION_JSON)
    public Attached attach(@Valid @NotNull AttachUser request) {
        return new Attached(directory.attachUsername(request.phone(), request.username()));
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

    public record AddPhone(
            @UsPhoneNumber String phone,
            @NotBlank @Size(max = 40) String name,
            @NotBlank @Pattern(regexp = "inv[0-9a-f]{32}") String username,
            @NotBlank @Pattern(regexp = "dashboard") String role) {
    }

    public record Added(boolean added) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WhitelistPhone(@UsPhoneNumber String phone, @Size(max = 40) String name) {
    }

    public record AttachUser(
            @UsPhoneNumber String phone,
            @NotBlank @Pattern(regexp = "inv[0-9a-f]{32}") String username) {
    }

    public record Attached(boolean attached) {
    }
}
