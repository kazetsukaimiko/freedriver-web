package io.freedriver.sms.api;

import io.freedriver.sms.validation.UsPhoneNumber;

public record SendRequest(@UsPhoneNumber String phone) {
}
