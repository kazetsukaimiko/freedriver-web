package io.freedriver.sms.phones;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/** Production sign-in times. One document per number in {@code sign-ins}. */
@ApplicationScoped
@IfBuildProfile(anyOf = {"prod", "dev"})
public class MongoSignInRecords implements SignInRecords {

    private final MongoCollection<Document> signIns;

    @Inject
    public MongoSignInRecords(
            MongoClient mongo,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.signIns = mongo.getDatabase(database).getCollection(SignInDocuments.COLLECTION);
        this.signIns.createIndex(Indexes.ascending("phone"), new IndexOptions().unique(true));
    }

    @Override
    public Instant get(String phone) {
        Document document = signIns.find(Filters.eq("_id", phone)).first();
        return document == null ? null : SignInDocuments.acceptedAt(document);
    }

    @Override
    public void put(String phone, Instant when) {
        signIns.replaceOne(
                Filters.eq("_id", phone),
                SignInDocuments.toDocument(phone, when),
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void delete(String phone) {
        signIns.deleteOne(Filters.eq("_id", phone));
    }
}
