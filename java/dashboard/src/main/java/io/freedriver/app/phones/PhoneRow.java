package io.freedriver.app.phones;

import java.time.Instant;

/** One number on the sms list, as the admin page shows it. Last sign-in is unset until it is recorded. */
public record PhoneRow(
        String phone,
        String name,
        String role,
        Instant addedAt,
        Instant consentedAt,
        Instant lastSignIn) {
}
