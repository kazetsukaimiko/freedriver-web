package io.freedriver.architecture.fixtures.apiquality;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** A record method that reaches a method through method handles. */
public record Handles(String name) {
    public Object find(Object target) throws Throwable {
        MethodHandle handle = MethodHandles.lookup()
                .findVirtual(target.getClass(), "toString", MethodType.methodType(String.class));
        return handle.invoke(target);
    }
}
