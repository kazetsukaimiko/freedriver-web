package io.freedriver.mqtt.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Entry ids are unique inside the list, and none of them equals the list id. */
@Documented
@Constraint(validatedBy = DistinctEntryIdsValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistinctEntryIds {

    String message() default "entry ids must be unique and differ from listId";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
