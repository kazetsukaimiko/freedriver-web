package io.freedriver.architecture.fixtures.apiquality;

/** The lambda implements service code and calls an API interface from its body. */
public class ServiceFnHold {
    ServiceFn held = ops -> ops.apply("id");
}
