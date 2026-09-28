package io.freedriver.sms.phones;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** A number on the phone list and the Keycloak user it signs in as. */
public record PhoneEntry(
        @UsPhoneNumber String phone,
        @NotBlank @Size(max = 40) String name,
        @NotBlank @Size(max = 128) String username,
        @NotBlank String role,
        @NotNull Instant addedAt) {
}
