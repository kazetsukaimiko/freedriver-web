package io.freedriver.sms.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a resource method whose JSON body carries a {@code phone} that the app-wide limits key on. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PhoneRateLimited {

    Kind value();

    enum Kind {
        /** Per-number sends in a window, then the service-wide daily cap. */
        SEND,
        /** Per-number wrong codes in a window. */
        CODE_CHECK
    }
}
