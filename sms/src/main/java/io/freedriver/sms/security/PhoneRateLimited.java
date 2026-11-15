package io.freedriver.sms.security;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the app-wide phone limits to a resource method whose validated request carries a
 * {@code phone}. {@link Kind#SEND} binds {@link SendLimitInterceptor}; {@link Kind#CODE_CHECK} binds
 * {@link CodeCheckLimitInterceptor}. The limits count listed and unlisted numbers the same.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PhoneRateLimited {

    Kind value();

    enum Kind {
        /** Per-number sends in a window, then the service-wide daily cap. */
        SEND,
        /** Per-number wrong codes in a window. */
        CODE_CHECK
    }

    /** After request validation, so a request that fails validation is refused with 400 and counts nothing. */
    int PRIORITY = jakarta.interceptor.Interceptor.Priority.PLATFORM_AFTER + 900;
}
