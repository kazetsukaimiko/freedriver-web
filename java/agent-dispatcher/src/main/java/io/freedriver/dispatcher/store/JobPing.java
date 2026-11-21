package io.freedriver.dispatcher.store;


/**
 * Notified after a job is queued. The hostname is the only fact the notifier receives.
 */
public interface JobPing {

    JobPing NONE = hostname -> { };

    void queued(String hostname);
}
