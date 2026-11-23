package io.freedriver.architecture.fixtures.apiquality;

/** A constant holder with a method, which an API module refuses. */
public final class HolderWithMethod {
    public static final String NAME = "n";

    private HolderWithMethod() {
    }

    public static String name() {
        return NAME;
    }
}
