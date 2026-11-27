package io.freedriver.architecture.fixtures.apiquality;

/**
 * A nested anonymous class extends a named implementation. The line names this class, once.
 */
public class AnonImplNested {
    void run() {
        new Runnable() {
            public void run() {
                new NamedImpl() {
                }.hashCode();
            }
        }.hashCode();
    }
}
