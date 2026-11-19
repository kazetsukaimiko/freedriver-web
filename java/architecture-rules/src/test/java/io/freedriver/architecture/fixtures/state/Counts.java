package io.freedriver.architecture.fixtures.state;

/** A field that changes after construction. */
public class Counts {

    private int count;

    public int next() {
        return ++count;
    }
}
