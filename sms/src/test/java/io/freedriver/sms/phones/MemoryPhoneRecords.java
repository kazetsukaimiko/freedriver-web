package io.freedriver.sms.phones;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared allowlist for tests. Two directories on one instance see the same insert or delete. */
final class MemoryPhoneRecords implements PhoneRecords {

    private final Map<String, PhoneEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<PhoneEntry> find(String phone) {
        return Optional.ofNullable(entries.get(phone));
    }

    @Override
    public synchronized List<PhoneEntry> list() {
        return List.copyOf(entries.values());
    }

    @Override
    public synchronized boolean insert(PhoneEntry entry) {
        if (entries.containsKey(entry.phone())) {
            return false;
        }
        entries.put(entry.phone(), entry);
        return true;
    }

    @Override
    public synchronized boolean replace(PhoneEntry entry) {
        if (!entries.containsKey(entry.phone())) {
            return false;
        }
        entries.put(entry.phone(), entry);
        return true;
    }

    @Override
    public synchronized boolean delete(String phone) {
        return entries.remove(phone) != null;
    }
}
