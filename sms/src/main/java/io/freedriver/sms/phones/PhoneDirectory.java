package io.freedriver.sms.phones;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The phone allowlist: the only numbers that can get a code. */
@ApplicationScoped
public class PhoneDirectory {

    static final String FILE = "phones.json";
    static final String PENDING_USERNAME = "pending";

    private final PhoneRecords records;

    @Inject
    public PhoneDirectory(PhoneRecords records) {
        this.records = records;
    }

    public PhoneDirectory(Path dataDir, ObjectMapper json) {
        this(new FilePhoneRecords(dataDir, json));
    }

    public synchronized Optional<PhoneEntry> find(String phone) {
        return records.find(phone);
    }

    public synchronized List<PhoneEntry> list() {
        return records.list();
    }

    /** Adds the number; false when it is already on the list. */
    public synchronized boolean add(@Valid @NotNull PhoneEntry entry) {
        return records.insert(entry);
    }

    /**
     * Lists the number before its Keycloak user exists. Role is {@code dashboard}.
     * A null or blank name is stored as the masked number. False when the number is already listed.
     */
    public synchronized boolean whitelist(String phone, String name) {
        if (phone == null || !phone.matches("\\+1[0-9]{10}")) {
            throw new IllegalArgumentException("phone");
        }
        if (records.find(phone).isPresent()) {
            return false;
        }
        String label = name == null ? "" : name.strip();
        if (label.isEmpty()) {
            label = "***" + phone.substring(phone.length() - 4);
        }
        if (label.length() > 40) {
            throw new IllegalArgumentException("name");
        }
        return records.insert(new PhoneEntry(phone, label, PENDING_USERNAME, "dashboard", java.time.Instant.now()));
    }

    /** Replaces the pending username. False when the row is missing or the username is not {@code inv} plus hex. */
    public synchronized boolean attachUsername(String phone, String username) {
        PhoneEntry existing = records.find(phone).orElse(null);
        if (existing == null || username == null || !username.matches("inv[0-9a-f]{32}")) {
            return false;
        }
        if (username.equals(existing.username())) {
            return true;
        }
        if (!PENDING_USERNAME.equals(existing.username())) {
            return false;
        }
        return records.replace(new PhoneEntry(phone, existing.name(), username, existing.role(), existing.addedAt()));
    }

    /** Takes the number off the list. Its agreement records stay in the {@link ConsentLedger}. */
    public synchronized boolean remove(String phone) {
        return records.delete(phone);
    }
}
