package io.freedriver.app.jobs;

import io.freedriver.dispatcher.model.AssignmentPage;
import io.freedriver.dispatcher.model.AssignmentView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Every assignment the dispatcher holds, newest first, read page by page from the dispatcher, which
 * owns the assignments. A refusal or a transport failure is {@link DispatcherUnavailable}.
 * No JAX-RS type leaves this class.
 */
@ApplicationScoped
public class DispatcherJobs {

    private static final Logger LOG = Logger.getLogger(DispatcherJobs.class);
    static final int PAGE = 200;
    static final int MOST_PAGES = 50;

    private final DispatcherAdmin dispatcher;

    @Inject
    DispatcherJobs(@RestClient DispatcherAdmin dispatcher) {
        this.dispatcher = dispatcher;
    }

    public List<AssignmentView> all() {
        List<AssignmentView> rows = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MOST_PAGES; page++) {
            AssignmentPage next = fetch(cursor);
            rows.addAll(next.assignments());
            cursor = next.next();
            if (cursor == null) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    private AssignmentPage fetch(String cursor) {
        try {
            return dispatcher.jobs(PAGE, cursor);
        } catch (DispatcherUnavailable e) {
            LOG.warnf("Dispatcher admin jobs refused: %s", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            LOG.warnf("Dispatcher admin jobs failed: %s", e.getClass().getSimpleName());
            throw new DispatcherUnavailable("dispatcher admin jobs failed", e);
        }
    }
}
