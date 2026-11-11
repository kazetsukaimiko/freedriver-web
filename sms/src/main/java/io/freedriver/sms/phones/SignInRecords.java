package io.freedriver.sms.phones;

import java.time.Instant;

/** Last accepted code time per number. Each call reads the store. */
interface SignInRecords {

    Instant get(String phone);

    void put(String phone, Instant when);

    void delete(String phone);
}
