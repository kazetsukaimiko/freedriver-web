package io.freedriver.mqtt.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** One appliance entry per target and delay. An absent delay matches {@code PT0S}. */
@Documented
@Constraint(validatedBy = DistinctTargetDelayValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistinctTargetDelay {

    String message() default "one appliance entry per target and delay";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
