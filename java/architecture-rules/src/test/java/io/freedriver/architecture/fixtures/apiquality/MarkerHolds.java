package io.freedriver.architecture.fixtures.apiquality;

/** The API interface is the intersection marker, not the lambda's primary type. */
public class MarkerHolds {
    Object go() {
        return (Box & ApiTag) id -> id;
    }
}
