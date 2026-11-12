package io.freedriver.sms.security;

import java.time.LocalDate;

/**
 * Production stores the UTC-day count as one document per date in the dashboard Mongo collection {@code daily-send-cap}.
 */
interface DailySendLedger {

    /** True when this UTC day still had room and the stored count now includes this send. */
    boolean tryAcquire(LocalDate day, int cap);

    void clear();
}
