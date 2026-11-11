package io.freedriver.dispatcher;

import org.bson.Document;

import java.util.List;

/**
 * The only stored state for jobs, hosts, and submit locks.
 * Callers read it again after each write. It is not a cache.
 */
interface JobDocuments {

    String JOBS = "agent-jobs";
    String DEVICES = "agent-devices";
    String LOCKS = "agent-locks";

    void replace(String collection, String id, Document document);

    Document findById(String collection, String id);

    List<Document> find(String collection);

    List<Document> findEqual(String collection, String field, String value);

    /** Replace the row when every filter field matches, including {@code _id}. */
    boolean replaceMatching(String collection, Document filter, Document document);

    boolean insertNew(String collection, String id, Document document);

    boolean delete(String collection, String id);
}
