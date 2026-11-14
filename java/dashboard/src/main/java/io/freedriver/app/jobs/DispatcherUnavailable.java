package io.freedriver.app.jobs;

/** The dispatcher refused the admin job list or could not be reached. The portal answers 502. */
public class DispatcherUnavailable extends RuntimeException {

    public DispatcherUnavailable(String message) {
        super(message);
    }

    public DispatcherUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
