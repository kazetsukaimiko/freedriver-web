package io.freedriver.mqtt.contract;

import java.util.UUID;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Accepts only the lowercase 8-4-4-4-12 form {@link UUID#toString()} returns. */
public final class CanonicalUuidValidator implements ConstraintValidator<CanonicalUuid, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
