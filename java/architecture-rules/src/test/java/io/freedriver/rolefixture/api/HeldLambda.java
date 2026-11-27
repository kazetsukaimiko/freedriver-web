package io.freedriver.rolefixture.api;

import java.util.function.Supplier;

/** An anonymous implementation created inside a lambda. */
public interface HeldLambda {
    default HeldApi inLambda() {
        Supplier<HeldApi> supplier = () -> new HeldApi() {
        };
        return supplier.get();
    }
}
