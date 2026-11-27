package io.freedriver.architecture.fixtures.apiquality;

/** An enum with a non-final field. */
public enum LooseEnum {
    A;

    int n;

    public int n() {
        return n;
    }
}
