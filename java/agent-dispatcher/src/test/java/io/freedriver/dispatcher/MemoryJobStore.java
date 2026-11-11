package io.freedriver.dispatcher;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Clock;

/**
 * Test stand-in for {@link MongoJobStore}. Same document calls, a local collection,
 * and it is not on the production classpath.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class MemoryJobStore extends DelegatingJobStore implements JobStore {

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    MemoryJobStore() {
    }

    @Inject
    MemoryJobStore(OpenBotJobPing ping) {
        super(new DocumentJobStore(new MemoryJobDocuments(), Clock.systemUTC(), ping));
    }
}
