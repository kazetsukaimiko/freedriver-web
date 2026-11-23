package io.freedriver.architecture.fixtures.apiquality;

/** A record whose static factory still leaves it a contract type. */
public record RecordFactory(String name) {
    public static RecordFactory of(String name) {
        return new RecordFactory(name);
    }
}
