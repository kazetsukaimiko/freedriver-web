package io.freedriver.architecture.fixtures.apiquality;

/** The lambda sits in a member class, so the line names that member class. */
public class MemberHolds {
    class Inner {
        final Callback held = id -> id;
    }
}
