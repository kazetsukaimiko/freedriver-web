package io.freedriver.stereotypes;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares state that lives in this process's memory: a field, or every field of a class, whose
 * value one replica holds and another replica, or the same one after a restart, never sees.
 *
 * <p>The architecture rules let an application or adapter class keep a mutable field (a
 * collection, an atomic, a lock, an executor, a {@code CompletableFuture}, or any non-final field)
 * only when the field or its class carries this annotation. {@link #value()} is the reason that
 * state is correct in one process, for example "a lazily built HTTP client; each replica builds
 * its own".
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface ProcessLocal {

    /** Why this state is correct while it lives in one process's memory. */
    String value();
}
