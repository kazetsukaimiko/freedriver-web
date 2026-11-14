package io.freedriver.sms.model;

import io.freedriver.sms.validation.UsPhoneNumber;
import jakarta.validation.constraints.NotBlank;

/** The invite page's agreement and code request. {@code wording} is the exact text the page showed. */
public record InviteSend(@UsPhoneNumber String phone, @NotBlank String wording, @NotBlank String inviteId) {
}
