package io.freedriver.architecture.fixtures.apiquality;

/** The anonymous class sits in a member class, so the line names that member class. */
public class AnonImplMember {
    static class Inner {
        Ordinary run() {
            return new Ordinary() {
            };
        }
    }
}
