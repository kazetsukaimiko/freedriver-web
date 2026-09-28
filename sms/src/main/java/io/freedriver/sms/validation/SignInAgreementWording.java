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

/** The sign-in agreement wording shown under the number field, character for character. */
@Documented
@NotNull
@Pattern(regexp = "\\Q" + SignInAgreementWording.TEXT + "\\E")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SignInAgreementWording {

    String TEXT = "By tapping Send code, you agree to receive a one-time sign-in code by text from Freedriver. "
            + "Message and data rates may apply. Reply STOP to opt out.";

    String message() default "must be the sign-in agreement wording";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
