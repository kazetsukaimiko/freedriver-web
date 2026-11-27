package io.freedriver.rolefixture.api;

/** An API interface whose constants are an enum and a record. */
public interface HeldValues {
    HeldKind KIND = HeldKind.ONE;
    HeldBox BOX = new HeldBox("x");
}
