package io.freedriver.app.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** A resource method that returns {@code jakarta.ws.rs.core.Response}. The value is why. */
@Retention(RUNTIME)
@Target(METHOD)
public @interface ReturnsResponse {
    String value();
}
