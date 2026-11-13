package io.freedriver.sms.model;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An invited number after its code is verified. Role is {@code dashboard} only. */
public record AddPhone(
        @UsPhoneNumber String phone,
        @NotBlank @Size(max = 40) String name,
        @NotBlank @Pattern(regexp = "inv[0-9a-f]{32}") String username,
        @NotBlank @Pattern(regexp = "dashboard") String role) {
}
