package io.freedriver.architecture.fixtures.apiquality;

/** An anonymous class implements an API interface. The anonymous class is not a subject. */
public class AnonImplements {
    Ordinary open() {
        return new Ordinary() {
        };
    }
}
