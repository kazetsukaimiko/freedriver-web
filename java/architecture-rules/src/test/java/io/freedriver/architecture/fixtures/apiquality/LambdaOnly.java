package io.freedriver.architecture.fixtures.apiquality;

/** The only link to an API interface is a lambda. The line names this class. */
public class LambdaOnly {
    Callback held = id -> id;
}
