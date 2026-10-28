package io.freedriver.app.instances;

/** The Keycloak user stage failed. The HTTP body is cause {@code broker}. */
public class BrokerRefusedException extends RuntimeException {

    public BrokerRefusedException() {
        super("broker refused");
    }
}
