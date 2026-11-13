package io.freedriver.sms.model;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The username of the user created for a listed number. */
public record AttachUser(
        @UsPhoneNumber String phone,
        @NotBlank @Pattern(regexp = "inv[0-9a-f]{32}") String username) {
}
