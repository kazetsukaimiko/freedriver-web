package jakarta.enterprise.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for the CDI scope {@code @ApplicationScoped}, with its name, for the fixtures of a bean
 * that keeps state. The rules match scopes by name, so this module needs no Jakarta dependency.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ApplicationScoped {
}
