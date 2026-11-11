package io.freedriver.dispatcher;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/** MongoDB collections. A failed write fails the call. Nothing is kept beside these rows. */
final class MongoJobDocuments implements JobDocuments {

    private final MongoClient client;
    private final String database;

    MongoJobDocuments(MongoClient client, String database) {
        this.client = client;
        this.database = database;
    }

    @Override
    public void replace(String collection, String id, Document document) {
        collection(collection).replaceOne(eq("_id", id), copy(document, id),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    @Override
    public Document findById(String collection, String id) {
        return collection(collection).find(eq("_id", id)).first();
    }

    @Override
    public List<Document> find(String collection) {
        List<Document> rows = new ArrayList<>();
        for (Document document : collection(collection).find()) {
            rows.add(document);
        }
        return rows;
    }

    @Override
    public List<Document> findEqual(String collection, String field, String value) {
        List<Document> rows = new ArrayList<>();
        for (Document document : collection(collection).find(eq(field, value))) {
            rows.add(document);
        }
        return rows;
    }

    @Override
    public boolean replaceMatching(String collection, Document filter, Document document) {
        Object id = filter.get("_id");
        if (id == null) {
            return false;
        }
        List<Bson> parts = new ArrayList<>();
        for (MapEntry entry : entries(filter)) {
            parts.add(eq(entry.key, entry.value));
        }
        Document next = copy(document, id.toString());
        return collection(collection).findOneAndReplace(and(parts), next) != null;
    }

    @Override
    public boolean insertNew(String collection, String id, Document document) {
        try {
            collection(collection).insertOne(copy(document, id));
            return true;
        } catch (MongoWriteException failure) {
            if (failure.getError().getCode() == 11000) {
                return false;
            }
            throw failure;
        }
    }

    @Override
    public boolean delete(String collection, String id) {
        return collection(collection).deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }

    private MongoCollection<Document> collection(String name) {
        return client.getDatabase(database).getCollection(name);
    }

    private static Document copy(Document document, String id) {
        Document copy = new Document(document);
        copy.put("_id", id);
        return copy;
    }

    private static List<MapEntry> entries(Document filter) {
        List<MapEntry> entries = new ArrayList<>();
        for (java.util.Map.Entry<String, Object> entry : filter.entrySet()) {
            entries.add(new MapEntry(entry.getKey(), entry.getValue()));
        }
        return entries;
    }

    private record MapEntry(String key, Object value) {
    }
}
