package io.freedriver.app.schedule;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;

/** {@code scheduled-switches}. A replica claims one due row at a time with a lease. */
@ApplicationScoped
public class ScheduledSwitchRepository implements PanacheMongoRepositoryBase<ScheduledSwitch, String> {

    private final AtomicBoolean indexed = new AtomicBoolean();

    /**
     * The {@code dueAt} index and a non-unique {@code subject} index, created by the first claim or
     * the first count rather than at startup, so the dashboard starts while Mongo is unreachable.
     * {@code indexed} flips only after both creates succeed. Creating an index that exists is a no-op.
     */
    private void ensureIndex() {
        if (!indexed.get()) {
            mongoCollection().createIndex(Indexes.ascending("dueAt"));
            mongoCollection().createIndex(Indexes.ascending("subject"));
            indexed.set(true);
        }
    }

    /**
     * Inserts {@code row} already held: {@code claimedUntil} is {@code now} plus one poller lease and
     * {@code attempts} is 0, so {@link #claim} skips it until {@link #release}.
     */
    public ScheduledSwitch storeHeld(ScheduledSwitch row, Instant now) {
        row.claimedUntil = now.plus(ScheduledSwitchPoller.LEASE);
        row.attempts = 0;
        persist(row);
        return row;
    }

    /** Documents in {@code scheduled-switches} whose {@code subject} equals {@code subject}. */
    public long countBySubject(String subject) {
        ensureIndex();
        return mongoCollection().countDocuments(eq("subject", subject));
    }

    /** Clears the hold on {@code id} so the poller can claim the row. */
    public void release(String id) {
        mongoCollection().updateOne(eq("_id", id), Updates.unset("claimedUntil"));
    }

    /**
     * Claims the earliest due row that no replica holds: sets {@code claimedUntil} to
     * {@code now + lease} and adds one to {@code attempts}. Null when no row is free.
     */
    ScheduledSwitch claim(Instant now, Duration lease) {
        ensureIndex();
        Date at = Date.from(now);
        return mongoCollection().findOneAndUpdate(
                and(lte("dueAt", at), or(eq("claimedUntil", null), lte("claimedUntil", at))),
                Updates.combine(Updates.set("claimedUntil", Date.from(now.plus(lease))), Updates.inc("attempts", 1)),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("dueAt")).returnDocument(ReturnDocument.AFTER));
    }
}
