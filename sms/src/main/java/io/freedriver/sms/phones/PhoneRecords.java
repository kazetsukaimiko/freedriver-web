package io.freedriver.sms.phones;

import java.util.List;
import java.util.Optional;

/** The phone allowlist. Each call reads the store. There is no process-lifetime copy. */
interface PhoneRecords {

    Optional<PhoneEntry> find(String phone);

    List<PhoneEntry> list();

    /** False when the number is already stored. */
    boolean insert(PhoneEntry entry);

    /** False when the number is not stored. */
    boolean replace(PhoneEntry entry);

    /** False when the number is not stored. */
    boolean delete(String phone);
}
