package io.freedriver.dispatcher;

/**
 * Notified after a job is queued. The hostname is the only fact the notifier receives.
 */
interface JobPing {

    JobPing NONE = hostname -> { };

    void queued(String hostname);
}
