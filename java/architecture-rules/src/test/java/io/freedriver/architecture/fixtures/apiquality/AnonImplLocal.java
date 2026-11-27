package io.freedriver.architecture.fixtures.apiquality;

/** The anonymous class sits in a local class, so the line names that local class. */
public class AnonImplLocal {
    void run() {
        class Holder {
            Ordinary go() {
                return new Ordinary() {
                };
            }
        }
        new Holder().go();
    }
}
