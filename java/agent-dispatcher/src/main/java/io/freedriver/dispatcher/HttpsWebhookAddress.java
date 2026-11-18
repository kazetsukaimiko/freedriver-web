package io.freedriver.dispatcher;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;

/**
 * A configured webhook address: scheme {@code https}, a host, and an authority free of user-info.
 * It sits on the type inside an {@code Optional}, so an absent value passes.
 */
@Documented
@Constraint(validatedBy = HttpsWebhookAddress.Rule.class)
@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.RUNTIME)
@interface HttpsWebhookAddress {

    String message() default "must be an https address with a host and without user-info";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The rule, read from the parsed {@link URI}. */
    class Rule implements ConstraintValidator<HttpsWebhookAddress, URI> {

        @Override
        public boolean isValid(URI address, ConstraintValidatorContext context) {
            return address == null
                    || "https".equalsIgnoreCase(address.getScheme())
                    && address.getHost() != null
                    && address.getRawUserInfo() == null;
        }
    }
}
