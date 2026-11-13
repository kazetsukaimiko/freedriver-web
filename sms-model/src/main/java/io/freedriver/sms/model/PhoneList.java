package io.freedriver.sms.model;

import java.util.List;

/** The phone list, oldest first. */
public record PhoneList(List<ListedPhone> phones) {
}
