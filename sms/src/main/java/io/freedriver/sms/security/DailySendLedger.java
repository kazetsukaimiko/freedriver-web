package io.freedriver.sms.security;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.inc;

/** The UTC-day send count, one {@code daily-send-cap} document per date. Every replica shares it. */
@ApplicationScoped
public class DailySendLedger implements PanacheMongoRepositoryBase<SendDay, String> {

    /** True when this UTC day still had room and the stored count now includes this send. */
    boolean tryAcquire(LocalDate day, int cap) {
        if (cap <= 0) {
            return false;
        }
        String id = day.toString();
        if (incrementIfUnder(id, cap)) {
            return true;
        }
        try {
            SendDay first = new SendDay();
            first.id = id;
            first.used = 1;
            persist(first);
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw ex;
            }
        }
        return incrementIfUnder(id, cap);
    }

    /** The conditional {@code $inc}: only a day still under the cap counts the send. */
    private boolean incrementIfUnder(String id, int cap) {
        return mongoCollection().findOneAndUpdate(
                and(eq("_id", id), lt("used", cap)),
                inc("used", 1),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)) != null;
    }

    void clear() {
        deleteAll();
    }
}
