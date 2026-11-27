package io.freedriver.architecture.fixtures.apiquality;

/** Service code whose anonymous class extends a named implementation. */
public class AnonImplBase {
    Ordinary run() {
        return new NamedImpl() {
        };
    }
}
