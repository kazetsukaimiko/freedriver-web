package io.freedriver.mqtt.contract;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * An optional delay: absent, any zero, or from one second through
 * {@link ApplianceSchemas#MAX_DELAY_SECONDS}. Compared with {@link java.time.Duration#compareTo}.
 */
@Documented
@Constraint(validatedBy = DelayWindowValidator.class)
@Target({
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT,
        ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface DelayWindow {

    String message() default "delay must be zero or from PT1S through MAX_DELAY_SECONDS";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
