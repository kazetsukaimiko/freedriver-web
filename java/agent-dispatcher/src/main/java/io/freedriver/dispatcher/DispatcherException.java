package io.freedriver.dispatcher;

public final class DispatcherException extends RuntimeException {

    public enum Kind {
        BAD_REQUEST,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT
    }

    private final Kind kind;

    public DispatcherException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
