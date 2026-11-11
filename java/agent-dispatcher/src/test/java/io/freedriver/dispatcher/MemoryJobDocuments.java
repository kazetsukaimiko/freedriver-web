package io.freedriver.dispatcher;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Document collection for tests. Production uses {@link MongoJobDocuments}.
 * This is the collection itself, not a cache in front of another store.
 */
final class MemoryJobDocuments implements JobDocuments {

    private final Map<String, Map<String, Document>> collections = new ConcurrentHashMap<>();
    private final Object waitLock = new Object();

    private Map<String, Document> collection(String name) {
        return collections.computeIfAbsent(name, key -> new ConcurrentHashMap<>());
    }

    @Override
    public synchronized void replace(String collection, String id, Document document) {
        collection(collection).put(id, copy(document, id));
    }

    @Override
    public synchronized Document findById(String collection, String id) {
        Document found = collection(collection).get(id);
        return found == null ? null : new Document(found);
    }

    @Override
    public synchronized List<Document> find(String collection) {
        List<Document> rows = new ArrayList<>();
        for (Document document : collection(collection).values()) {
            rows.add(new Document(document));
        }
        return rows;
    }

    @Override
    public synchronized List<Document> findEqual(String collection, String field, String value) {
        List<Document> rows = new ArrayList<>();
        for (Document document : find(collection)) {
            if (Objects.equals(value, document.getString(field))) {
                rows.add(document);
            }
        }
        return rows;
    }

    @Override
    public synchronized boolean replaceMatching(String collection, Document filter, Document document) {
        Object id = filter.get("_id");
        if (id == null) {
            return false;
        }
        Document current = collection(collection).get(id.toString());
        if (current == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            if (!Objects.equals(current.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        collection(collection).put(id.toString(), copy(document, id.toString()));
        return true;
    }

    @Override
    public synchronized boolean insertNew(String collection, String id, Document document) {
        Map<String, Document> rows = collection(collection);
        if (rows.containsKey(id)) {
            return false;
        }
        rows.put(id, copy(document, id));
        return true;
    }

    @Override
    public synchronized boolean delete(String collection, String id) {
        return collection(collection).remove(id) != null;
    }

    @Override
    public Object waitLock() {
        return waitLock;
    }

    private static Document copy(Document document, String id) {
        Document copy = new Document(document);
        copy.put("_id", id);
        return copy;
    }
}
