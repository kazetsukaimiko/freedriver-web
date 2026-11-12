package io.freedriver.sms.security;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.inc;

/**
 * {@code daily-send-cap} stores one document per UTC date. The document id is the date string.
 */
@ApplicationScoped
@IfBuildProfile(anyOf = {"prod", "dev"})
public class MongoDailySendLedger implements DailySendLedger {

    static final String COLLECTION = "daily-send-cap";

    private final MongoCollection<Document> days;

    @Inject
    public MongoDailySendLedger(
            MongoClient mongo,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.days = mongo.getDatabase(database).getCollection(COLLECTION);
    }

    @Override
    public boolean tryAcquire(LocalDate day, int cap) {
        if (cap <= 0) {
            return false;
        }
        String id = day.toString();
        if (incrementIfUnder(id, cap)) {
            return true;
        }
        try {
            days.insertOne(new Document("_id", id).append("used", 1));
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw ex;
            }
        }
        return incrementIfUnder(id, cap);
    }

    private boolean incrementIfUnder(String id, int cap) {
        Document updated = days.findOneAndUpdate(
                and(eq("_id", id), lt("used", cap)),
                inc("used", 1),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return updated != null;
    }

    @Override
    public void clear() {
        days.deleteMany(new Document());
    }
}
