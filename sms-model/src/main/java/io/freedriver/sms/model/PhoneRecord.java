package io.freedriver.sms.model;

import java.time.Instant;

/** One stored {@code phones} row, as the portal's records page shows it. */
public record PhoneRecord(String id, String phone, String name, String username, String role, Instant addedAt) {
}
