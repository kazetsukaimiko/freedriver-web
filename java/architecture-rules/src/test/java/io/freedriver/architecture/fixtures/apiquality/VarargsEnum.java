package io.freedriver.architecture.fixtures.apiquality;

/** An enum whose constructor takes a varargs array. */
public enum VarargsEnum {
    ONE("a", "b");

    private final String[] parts;

    VarargsEnum(String... parts) {
        this.parts = parts;
    }

    public int count() {
        return parts.length;
    }
}
