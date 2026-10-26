package io.freedriver.sms.http;

import io.freedriver.sms.validation.UsPhoneNumber;

/** Body of {@code POST /sms/invite/text}. */
public record InvitationRequest(@UsPhoneNumber String phone) {
}
