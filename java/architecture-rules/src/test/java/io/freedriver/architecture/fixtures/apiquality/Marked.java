package io.freedriver.architecture.fixtures.apiquality;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stands in for an allowed annotation whose member has a default, the way {@code @JsonProperty}
 * names {@code Access} and {@code OptBoolean} without the class using those types itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface Marked {
    Mode mode() default Mode.ON;

    enum Mode {
        ON, OFF
    }
}
