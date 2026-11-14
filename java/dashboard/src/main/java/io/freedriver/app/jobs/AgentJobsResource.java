package io.freedriver.app.jobs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Every dispatch assignment, from the dispatcher, which owns them. The HTTP contract is {@link AgentJobsApi}. */
@ApplicationScoped
public class AgentJobsResource implements AgentJobsApi {

    private final DispatcherJobs dispatcher;

    @Inject
    public AgentJobsResource(DispatcherJobs dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public JobList list() {
        return new JobList(dispatcher.all());
    }
}
