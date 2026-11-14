package io.freedriver.dispatcher.model;

import java.util.List;

/** One page of assignments across all callers, newest first. {@code next} is null on the last page. */
public record AssignmentPage(List<AssignmentView> assignments, String next) {
}
