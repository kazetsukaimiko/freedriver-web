package io.freedriver.mqtt.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** A cancel names exactly one of {@code target} or {@code cancelId}. */
@Documented
@Constraint(validatedBy = OneCancelSelectorValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneCancelSelector {

    String message() default "exactly one of target or cancelId";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
