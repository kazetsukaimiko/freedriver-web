package io.freedriver.architecture.fixtures.apiquality;

import java.util.function.Supplier;

/**
 * The same API interface, implemented by anonymous classes in a field, a static initializer,
 * a method and a lambda. One line, naming this class.
 */
public class AnonImplPlaces {
    Ordinary field = new Ordinary() {
    };

    static final Ordinary CONSTANT;

    static {
        CONSTANT = new Ordinary() {
        };
    }

    Ordinary method() {
        Supplier<Ordinary> supplier = () -> new Ordinary() {
        };
        return supplier.get();
    }
}
