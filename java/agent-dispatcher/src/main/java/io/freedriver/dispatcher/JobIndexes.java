package io.freedriver.dispatcher;

import io.freedriver.dispatcher.store.AssignmentRepository;
import io.freedriver.dispatcher.store.DeviceRepository;
import io.freedriver.dispatcher.store.SubmitLocks;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jmolecules.architecture.hexagonal.SecondaryAdapter;

import java.util.concurrent.TimeUnit;

/** Creates the dispatcher's indexes at startup. Creating an index that exists is a no-op. */
@SecondaryAdapter
@ApplicationScoped
class JobIndexes {

    void create(@Observes StartupEvent start, AssignmentRepository assignments, DeviceRepository devices,
                SubmitLocks locks) {
        var jobs = assignments.mongoCollection();
        jobs.createIndex(Indexes.compoundIndex(Indexes.ascending("dispatcherSubject"), Indexes.descending("createdAt")));
        jobs.createIndex(Indexes.ascending("deviceId", "status", "ordinal"));
        jobs.createIndex(Indexes.ascending("jobId"));
        jobs.createIndex(Indexes.descending("createdAt", "_id"));
        devices.mongoCollection().createIndex(Indexes.ascending("hostname"));
        locks.mongoCollection().createIndex(Indexes.ascending("expiresAt"),
                new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }
}
