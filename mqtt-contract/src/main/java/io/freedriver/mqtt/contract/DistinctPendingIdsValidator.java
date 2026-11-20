package io.freedriver.mqtt.contract;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Rejects a repeated id inside {@link ScheduledCommandsMessage#pending()}. */
public final class DistinctPendingIdsValidator
        implements ConstraintValidator<DistinctPendingIds, ScheduledCommandsMessage> {

    @Override
    public boolean isValid(ScheduledCommandsMessage value, ConstraintValidatorContext context) {
        if (value == null || value.pending() == null) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        for (PendingCommand entry : value.pending()) {
            if (entry == null || entry.id() == null) {
                return true;
            }
            if (!seen.add(entry.id())) {
                return false;
            }
        }
        return true;
    }
}
