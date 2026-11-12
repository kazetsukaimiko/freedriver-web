package io.freedriver.dispatcher.model;

import java.util.List;

/**
 * One page of a caller's jobs. {@code nextCursor} is null on the last page.
 */
public record JobPage(List<JobSummary> jobs, String nextCursor) {
}
