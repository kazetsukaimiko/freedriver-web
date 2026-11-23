package io.freedriver.dashboard.model;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * One command per appliance name and delay. An absent delay matches {@code PT0S},
 * the same comparison {@code DistinctTargetDelay} uses for an appliance entry.
 */
@Documented
@Constraint(validatedBy = DistinctApplianceDelayValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistinctApplianceDelay {

    String message() default "one command per appliance and delay";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
