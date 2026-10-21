package io.freedriver.sms.phones;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @NotNull Instant givenAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String inviteId) {

    public ConsentRecord(String phone, ConsentPurpose purpose, String wording, ConsentSource source, Instant givenAt) {
        this(phone, purpose, wording, source, givenAt, null);
    }
}
