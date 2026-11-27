package io.freedriver.architecture.fixtures.apiquality;

import java.util.function.Consumer;
import java.util.function.Function;

/** Lambdas in every place a holding class can write one. One pair, one line. */
public class LambdaPlaces {
    Callback field = id -> id;
    static Callback held;

    static {
        held = id -> id;
    }

    LambdaPlaces() {
        Callback local = id -> id;
        local.apply("id");
    }

    Callback method() {
        return id -> id;
    }

    void arg(Consumer<Callback> sink) {
        sink.accept(id -> id);
    }

    Callback nested() {
        Function<String, Callback> wrap = id -> (x -> x);
        return wrap.apply("id");
    }
}
