package io.freedriver.sms.phones;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * One recorded agreement: the number, what it covers, the exact wording shown, where it was
 * given and when. Records are only ever appended.
 */
public record ConsentRecord(
        @UsPhoneNumber String phone,
        @NotNull ConsentPurpose purpose,
        @NotBlank String wording,
        @NotNull ConsentSource source,
        @NotNull Instant givenAt) {
}
