package io.freedriver.architecture.fixtures.apiquality;

/** The class and an anonymous class implement the same API interface. One line. */
public class AnonImplAgain implements Ordinary {
    Ordinary more() {
        return new Ordinary() {
        };
    }
}
