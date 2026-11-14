package io.freedriver.sms.model;

import java.time.Instant;

/** One stored {@code sign-ins} row: when a listed number last accepted a code. */
public record SignInRecord(String id, String phone, Instant acceptedAt) {
}
