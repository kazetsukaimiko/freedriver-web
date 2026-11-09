package io.freedriver.dispatcher.model;

/**
 * One bot a host publishes. State is idle or busy. Activity is a short note.
 */
public record ReportedBot(String name, String state, String activity) {
}
