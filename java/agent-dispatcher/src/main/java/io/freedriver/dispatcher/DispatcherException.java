package io.freedriver.dispatcher;

public final class DispatcherException extends RuntimeException {

    public enum Kind {
        BAD_REQUEST(400),
        FORBIDDEN(403),
        NOT_FOUND(404),
        CONFLICT(409);

        private final int status;

        Kind(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
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
