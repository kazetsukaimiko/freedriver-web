package io.freedriver.app.schedule;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.or;

/** {@code scheduled-switches}. A replica claims one due row at a time with a lease. */
@ApplicationScoped
public class ScheduledSwitchRepository implements PanacheMongoRepositoryBase<ScheduledSwitch, String> {

    void index(@Observes StartupEvent start) {
        mongoCollection().createIndex(Indexes.ascending("dueAt"));
    }

    /**
     * Claims the earliest due row that no replica holds: sets {@code claimedUntil} to
     * {@code now + lease} and adds one to {@code attempts}. Null when no row is free.
     */
    ScheduledSwitch claim(Instant now, Duration lease) {
        Date at = Date.from(now);
        return mongoCollection().findOneAndUpdate(
                and(lte("dueAt", at), or(eq("claimedUntil", null), lte("claimedUntil", at))),
                Updates.combine(Updates.set("claimedUntil", Date.from(now.plus(lease))), Updates.inc("attempts", 1)),
                new FindOneAndUpdateOptions().sort(Sorts.ascending("dueAt")).returnDocument(ReturnDocument.AFTER));
    }
}
