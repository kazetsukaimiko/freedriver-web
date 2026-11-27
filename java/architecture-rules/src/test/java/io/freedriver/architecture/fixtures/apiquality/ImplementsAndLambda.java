package io.freedriver.architecture.fixtures.apiquality;

/** The class and a lambda implement the same API interface. One pair, one line. */
public class ImplementsAndLambda implements Callback {
    Callback again = id -> id;

    @Override
    public String apply(String id) {
        return id;
    }
}
