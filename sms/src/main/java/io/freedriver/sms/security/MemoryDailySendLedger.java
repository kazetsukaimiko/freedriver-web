package io.freedriver.sms.security;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Test-profile count. Production uses {@link MongoDailySendLedger}. */
@ApplicationScoped
@IfBuildProfile("test")
public class MemoryDailySendLedger implements DailySendLedger {

    private final Map<LocalDate, Integer> used = new HashMap<>();

    @Override
    public synchronized boolean tryAcquire(LocalDate day, int cap) {
        if (cap <= 0) {
            return false;
        }
        int count = used.getOrDefault(day, 0);
        if (count >= cap) {
            return false;
        }
        used.put(day, count + 1);
        return true;
    }

    @Override
    public synchronized void clear() {
        used.clear();
    }
}
