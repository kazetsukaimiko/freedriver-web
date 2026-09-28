package io.freedriver.sms.api;

import io.freedriver.sms.validation.SignInAgreementWording;

/** {@code {"wording":"By tapping Send code, ..."}}: the agreement the sign-in page showed when Send code was tapped. */
public record AgreementShown(@SignInAgreementWording String wording) {
}
