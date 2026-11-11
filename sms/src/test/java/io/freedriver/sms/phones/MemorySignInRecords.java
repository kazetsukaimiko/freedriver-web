package io.freedriver.sms.phones;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared sign-in times for tests. Two records on one instance see the same write. */
final class MemorySignInRecords implements SignInRecords {

    private final Map<String, Instant> times = new LinkedHashMap<>();

    @Override
    public synchronized Instant get(String phone) {
        return times.get(phone);
    }

    @Override
    public synchronized void put(String phone, Instant when) {
        times.put(phone, when);
    }

    @Override
    public synchronized void delete(String phone) {
        times.remove(phone);
    }
}
