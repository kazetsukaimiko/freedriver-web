package io.freedriver.mqtt.contract;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Rejects a repeated entry id and an entry id that copies {@code listId}. */
public final class DistinctEntryIdsValidator implements ConstraintValidator<DistinctEntryIds, ApplianceCommandList> {

    @Override
    public boolean isValid(ApplianceCommandList value, ConstraintValidatorContext context) {
        if (value == null || value.commands() == null) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        for (CommandListEntry entry : value.commands()) {
            if (entry == null || entry.id() == null) {
                return true;
            }
            if (!seen.add(entry.id()) || entry.id().equals(value.listId())) {
                return false;
            }
        }
        return true;
    }
}
