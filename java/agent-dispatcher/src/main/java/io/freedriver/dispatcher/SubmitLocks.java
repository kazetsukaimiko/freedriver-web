package io.freedriver.dispatcher;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lte;

/**
 * {@code agent-locks}: one in-flight submit wait per dispatcher subject. A TTL index on
 * {@code expiresAt} removes stale rows.
 */
@ApplicationScoped
public class SubmitLocks implements PanacheMongoRepositoryBase<SubmitLock, String> {

    /**
     * One upsert filtered on the subject and an expired {@code expiresAt}. A live lock makes the
     * filter miss, and the upsert's insert then fails on the duplicate id.
     *
     * @return false when another submit holds the lock
     */
    boolean acquire(String dispatcherSubject, Instant now, Duration ttl) {
        try {
            mongoCollection().updateOne(
                    and(eq("_id", dispatcherSubject), lte("expiresAt", Date.from(now))),
                    Updates.set("expiresAt", Date.from(now.plus(ttl))),
                    new UpdateOptions().upsert(true));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    void release(String dispatcherSubject) {
        deleteById(dispatcherSubject);
    }
}
