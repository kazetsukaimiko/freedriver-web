package io.freedriver.rolefixture.api;

/** An API enum that implements an API interface and gives a constant a class body. */
public enum HeldKind implements HeldMark {
    ONE {
        void tag() {
        }
    };

    @Override
    public String mark() {
        return "one";
    }
}
