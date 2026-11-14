package io.freedriver.sms.phones;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The phone allowlist: the only numbers that can get a code. Each change is one Mongo write. */
@ApplicationScoped
public class PhoneDirectory {

    static final String PENDING_USERNAME = "pending";

    private final PhoneRepository phones;

    @Inject
    public PhoneDirectory(PhoneRepository phones) {
        this.phones = phones;
    }

    public Optional<PhoneEntry> find(String phone) {
        return phones.find(phone);
    }

    public List<PhoneEntry> list() {
        return phones.list();
    }

    /** Adds the number; false when it is already on the list. */
    public boolean add(@Valid @NotNull PhoneEntry entry) {
        return phones.insert(entry);
    }

    /**
     * Lists the number before its Keycloak user exists. Role is {@code dashboard}.
     * A null or blank name is stored as the masked number. False when the number is already listed.
     */
    public boolean whitelist(String phone, String name) {
        if (phone == null || !phone.matches("\\+1[0-9]{10}")) {
            throw new IllegalArgumentException("phone");
        }
        if (phones.find(phone).isPresent()) {
            return false;
        }
        String label = name == null ? "" : name.strip();
        if (label.isEmpty()) {
            label = "***" + phone.substring(phone.length() - 4);
        }
        if (label.length() > 40) {
            throw new IllegalArgumentException("name");
        }
        return phones.insert(new PhoneEntry(phone, label, PENDING_USERNAME, "dashboard", Instant.now()));
    }

    /**
     * Replaces the pending username in one filtered update. False when the row is missing, holds
     * another username, or the username is not {@code inv} plus hex.
     */
    public boolean attachUsername(String phone, String username) {
        if (phone == null || username == null || !username.matches("inv[0-9a-f]{32}")) {
            return false;
        }
        return phones.attachUsername(phone, PENDING_USERNAME, username);
    }

    /** Takes the number off the list. Its agreement records stay in the {@link ConsentLedger}. */
    public boolean remove(String phone) {
        return phones.delete(phone);
    }
}
