package io.freedriver.architecture.fixtures.apiquality;

/** A functional interface of service code. Its body may call an API interface. */
public interface ServiceFn {
    String apply(Callback ops);
}
