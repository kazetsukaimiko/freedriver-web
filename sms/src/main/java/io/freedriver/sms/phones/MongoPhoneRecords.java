package io.freedriver.sms.phones;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Production allowlist. One document per number in {@code phones}, inserted or deleted on its own. */
@ApplicationScoped
@IfBuildProfile(anyOf = {"prod", "dev"})
public class MongoPhoneRecords implements PhoneRecords {

    private final MongoCollection<Document> phones;

    @Inject
    public MongoPhoneRecords(
            MongoClient mongo,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.phones = mongo.getDatabase(database).getCollection(PhoneDocuments.COLLECTION);
        this.phones.createIndex(Indexes.ascending("phone"), new IndexOptions().unique(true));
    }

    @Override
    public Optional<PhoneEntry> find(String phone) {
        Document document = phones.find(Filters.eq("_id", phone)).first();
        return document == null ? Optional.empty() : Optional.of(PhoneDocuments.toEntry(document));
    }

    @Override
    public List<PhoneEntry> list() {
        List<PhoneEntry> entries = new ArrayList<>();
        for (Document document : phones.find().sort(Sorts.ascending("addedAt"))) {
            entries.add(PhoneDocuments.toEntry(document));
        }
        return List.copyOf(entries);
    }

    @Override
    public boolean insert(PhoneEntry entry) {
        try {
            phones.insertOne(PhoneDocuments.toDocument(entry));
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw ex;
        }
    }

    @Override
    public boolean replace(PhoneEntry entry) {
        return phones.replaceOne(Filters.eq("_id", entry.phone()), PhoneDocuments.toDocument(entry))
                .getMatchedCount() == 1;
    }

    @Override
    public boolean delete(String phone) {
        return phones.deleteOne(Filters.eq("_id", phone)).getDeletedCount() == 1;
    }
}
