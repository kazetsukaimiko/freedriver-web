package io.freedriver.daemon;

/** A config still selects Grok mode, which this jar no longer runs. */
final class RetiredModeException extends RuntimeException {

    static final String MESSAGE = "openbot is the only mode";

    RetiredModeException() {
        super(MESSAGE);
    }
}
