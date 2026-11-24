package io.freedriver.dashboard.commandlists.api;

/**
 * A list-POST body failed the strict read: malformed JSON, a coerced scalar, an unknown field,
 * or a limit on {@link io.freedriver.dashboard.model.api.ApplianceCommandListRequest}.
 */
public final class InvalidCommandListException extends RuntimeException {

    public InvalidCommandListException(String message) {
        super(message);
    }

    public InvalidCommandListException(String message, Throwable cause) {
        super(message, cause);
    }
}
