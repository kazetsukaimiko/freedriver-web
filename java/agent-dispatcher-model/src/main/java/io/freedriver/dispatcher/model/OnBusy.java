package io.freedriver.dispatcher.model;

/**
 * What the caller wants when the named agent is already busy.
 * Stored on the job. The dispatcher reads it. This process does not notify differently.
 */
public enum OnBusy {
    supersede,
    queue
}
