package io.freedriver.daemon;

import io.freedriver.dispatcher.model.AssignmentView;
import io.freedriver.dispatcher.model.DeviceView;
import io.freedriver.dispatcher.model.FinishRequest;
import io.freedriver.dispatcher.model.HostReport;
import io.freedriver.dispatcher.model.StartRequest;
import io.freedriver.dispatcher.model.TakeRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The dispatcher's host API, in the contract's own types. A refusal is {@link DeviceRejected};
 * a call that did not complete is an {@link IllegalStateException}.
 */
interface HostJobs {

    DeviceView report(HostReport report);

    List<AssignmentView> take(TakeRequest request);

    /** The job as the dispatcher holds it; empty when the route answers 409. */
    Optional<AssignmentView> start(UUID assignmentId, StartRequest request);

    /** The job as the dispatcher holds it; empty when the route answers 409. */
    Optional<AssignmentView> finish(UUID assignmentId, FinishRequest request);
}
