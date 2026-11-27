package io.freedriver.architecture.fixtures.apiquality;

import java.util.function.Function;

/**
 * A method reference whose target type is a JDK functional interface.
 * The referenced method is on an API interface.
 */
public class CallsThrough {
    private final Callback ops;

    CallsThrough(Callback ops) {
        this.ops = ops;
    }

    String go() {
        return call(ops::apply);
    }

    private static String call(Function<String, String> request) {
        return request.apply("id");
    }
}
