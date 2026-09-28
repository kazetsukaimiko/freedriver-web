package io.freedriver.sms.api;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VerifyRequest(@UsPhoneNumber String phone, @NotNull @Size(max = 16) String code) {
}
