package io.freedriver.mqtt.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A string that is already in canonical UUID form: {@code UUID.fromString(s).toString().equals(s)}.
 * Null is left to {@code @NotNull} or {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = CanonicalUuidValidator.class)
@Target({
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface CanonicalUuid {

    String message() default "must be a lowercase canonical UUID";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
