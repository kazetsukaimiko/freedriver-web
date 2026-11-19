package io.quarkus.arc.profile;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for Quarkus's {@code @IfBuildProfile}, with its name and members, for the fixture of
 * a dev and test fake. The rules match the annotation by name, so this module needs no Quarkus.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface IfBuildProfile {

    String value() default "";

    String[] anyOf() default {};

    String[] allOf() default {};
}
