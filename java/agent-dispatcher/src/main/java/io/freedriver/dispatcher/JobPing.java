package io.freedriver.dispatcher;

import org.jmolecules.architecture.hexagonal.SecondaryPort;

/**
 * Notified after a job is queued. The hostname is the only fact the notifier receives.
 */
@SecondaryPort
interface JobPing {

    JobPing NONE = hostname -> { };

    void queued(String hostname);
}
