package io.freedriver.sms.model;

import io.freedriver.sms.validation.UsPhoneNumber;

public record RemovePhone(@UsPhoneNumber String phone) {
}
