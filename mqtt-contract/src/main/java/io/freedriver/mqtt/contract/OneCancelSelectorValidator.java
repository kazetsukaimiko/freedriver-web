package io.freedriver.mqtt.contract;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** A present JSON value, including a blank string, counts. Both and neither are refused. */
public final class OneCancelSelectorValidator implements ConstraintValidator<OneCancelSelector, CancelCommandEntry> {

    @Override
    public boolean isValid(CancelCommandEntry value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return (value.target() != null) ^ (value.cancelId() != null);
    }
}
