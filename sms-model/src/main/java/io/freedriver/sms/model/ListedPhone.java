package io.freedriver.sms.model;

import java.time.Instant;

/** One number on the list. {@code consentedAt} and {@code lastSignIn} are null until recorded. */
public record ListedPhone(
        String phone,
        String name,
        String role,
        Instant addedAt,
        Instant consentedAt,
        Instant lastSignIn) {
}
