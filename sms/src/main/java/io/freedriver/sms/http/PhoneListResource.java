package io.freedriver.sms.http;

import io.freedriver.sms.api.SmsPhonesApi;
import io.freedriver.sms.model.AddPhone;
import io.freedriver.sms.model.Added;
import io.freedriver.sms.model.AttachUser;
import io.freedriver.sms.model.Attached;
import io.freedriver.sms.model.ListedPhone;
import io.freedriver.sms.model.PhoneList;
import io.freedriver.sms.model.RemovePhone;
import io.freedriver.sms.model.Removed;
import io.freedriver.sms.model.WhitelistPhone;
import io.freedriver.sms.phones.ConsentLedger;
import io.freedriver.sms.phones.ConsentPurpose;
import io.freedriver.sms.phones.ConsentRecord;
import io.freedriver.sms.phones.PhoneDirectory;
import io.freedriver.sms.phones.PhoneEntry;
import io.freedriver.sms.phones.SignInTimes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Phone list for the portal. sms does not call Keycloak. Callers still present the shared secret.
 */
public class PhoneListResource implements SmsPhonesApi {

    @Inject
    PhoneDirectory directory;
    @Inject
    ConsentLedger consents;
    @Inject
    SignInTimes signIns;

    @Override
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

    @Override
    public Removed remove(RemovePhone request) {
        boolean removed = directory.remove(request.phone());
        if (removed) {
            signIns.forget(request.phone());
        }
        return new Removed(removed);
    }

    /** Puts an invited number on the list after its code is verified. Role is {@code dashboard} only. */
    @Override
    public Added add(AddPhone request) {
        boolean added = directory.add(new PhoneEntry(
                request.phone(), request.name(), request.username(), request.role(), Instant.now()));
        return new Added(added);
    }

    /** Lists a number before its Keycloak user exists. The role is always {@code dashboard}. */
    @Override
    public Added whitelist(WhitelistPhone request) {
        String name = request.name() == null ? null : request.name().strip();
        boolean added = directory.whitelist(request.phone(), name == null || name.isEmpty() ? null : name);
        return new Added(added);
    }

    /** Records the username of the user created for a listed number. */
    @Override
    public Attached attach(AttachUser request) {
        return new Attached(directory.attachUsername(request.phone(), request.username()));
    }

    private Instant consentedAt(String phone) {
        return consents.history(phone).stream()
                .filter(record -> record.purpose() == ConsentPurpose.SIGN_IN_CODES)
                .map(ConsentRecord::givenAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
