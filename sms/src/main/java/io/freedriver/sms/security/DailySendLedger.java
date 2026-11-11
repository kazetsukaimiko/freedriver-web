package io.freedriver.sms.security;

import java.time.LocalDate;

/**
 * The UTC-day SMS send count. Production is one Mongo document per day.
 * A process map is not that record, and Redis is not this counter.
 */
interface DailySendLedger {

    /** True when this UTC day still had room and the stored count now includes this send. */
    boolean tryAcquire(LocalDate day, int cap);

    void clear();
}
