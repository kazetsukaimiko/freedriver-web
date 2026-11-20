package io.freedriver.mqtt.contract;

import java.time.Duration;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Bounds a delay with {@link Duration#compareTo}. {@code toSeconds()} would truncate a fraction
 * and accept a value that is still over the cap or under one second.
 */
public final class DelayWindowValidator implements ConstraintValidator<DelayWindow, Duration> {

    private static final Duration MIN = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(ApplianceSchemas.MAX_DELAY_SECONDS);

    @Override
    public boolean isValid(Duration value, ConstraintValidatorContext context) {
        if (value == null || value.compareTo(Duration.ZERO) == 0) {
            return true;
        }
        return value.compareTo(MIN) >= 0 && value.compareTo(MAX) <= 0;
    }
}
