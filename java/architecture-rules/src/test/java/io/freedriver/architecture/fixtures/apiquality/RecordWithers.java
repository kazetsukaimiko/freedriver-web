package io.freedriver.architecture.fixtures.apiquality;

/** A record with a wither. */
public record RecordWithers(String name) {
    public RecordWithers withName(String next) {
        return new RecordWithers(next);
    }
}
