package io.freedriver.mcp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A record a tool takes as an argument, or as the entries of a list argument, whose JSON objects
 * carry only the record's components. {@link DeclaredToolArguments} refuses an object with any other
 * field, and {@link StrictInputSchemas} advertises {@code additionalProperties: false} on its schema.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface OnlyDeclaredFields {
}
