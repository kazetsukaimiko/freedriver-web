package io.freedriver.sms.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A US number in E.164 form: {@code +1} followed by ten digits. Keycloak normalizes the typed
 * number before calling sms, so any other country code or shape is refused here, before the
 * phone list, the agreement check or Twilio.
 */
@Documented
@NotNull
@Pattern(regexp = UsPhoneNumber.REGEX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface UsPhoneNumber {

    String REGEX = "^\\+1[0-9]{10}$";

    String message() default "must be a US phone number in +1XXXXXXXXXX form";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
