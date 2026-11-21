package io.freedriver.mqtt.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Ids in one scheduled snapshot are unique. */
@Documented
@Constraint(validatedBy = DistinctPendingIdsValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistinctPendingIds {

    String message() default "pending ids must be unique";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
