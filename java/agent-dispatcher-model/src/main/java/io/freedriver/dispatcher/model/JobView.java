package io.freedriver.dispatcher.model;

import java.util.List;
import java.util.UUID;

public record JobView(
        UUID jobId,
        String dispatcherSubject,
        Channel channel,
        String prompt,
        List<AssignmentView> assignments) {
}
