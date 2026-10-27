package io.freedriver.app.instances;

/** Label failed the contract. The HTTP body is cause {@code label}. */
public class InvalidLabelException extends RuntimeException {

    public InvalidLabelException() {
        super("label");
    }
}
