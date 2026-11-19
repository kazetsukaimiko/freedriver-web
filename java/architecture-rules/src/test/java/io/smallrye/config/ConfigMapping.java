package io.smallrye.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for SmallRye's {@code @ConfigMapping}, with its name, for the fixture of a configuration
 * interface. The rules match the annotation by name, so this module needs no Quarkus.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConfigMapping {

    String prefix() default "";
}
