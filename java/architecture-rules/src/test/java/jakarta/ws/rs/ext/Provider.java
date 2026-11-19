package jakarta.ws.rs.ext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A stand-in for {@code @jakarta.ws.rs.ext.Provider}. The layering rules match it by name, so the
 * architecture-rules module needs no JAX-RS dependency.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Provider {
}
