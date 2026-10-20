package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.UpdateAssignmentRequest;

import java.util.List;
import java.util.UUID;

interface DeviceApiClient {

    void register(String hostname, List<String> agents);

    void heartbeat();

    List<AssignmentView> poll();

    AssignmentView claim(UUID assignmentId);

    AssignmentView assignment(UUID assignmentId);

    AssignmentView update(UUID assignmentId, UpdateAssignmentRequest request);
}
