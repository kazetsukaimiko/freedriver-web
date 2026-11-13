package io.freedriver.sms.phones;

import com.mongodb.client.MongoClient;
import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production phone store on the Dev Services Mongo container. */
@QuarkusTest
class MongoPhoneRecordsTest {

    @Inject
    MongoPhoneRecords store;

    @Inject
    MongoClient mongo;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    @Test
    void a_number_is_written_read_and_deleted_through_the_collection() {
        String phone = TestNumbers.fresh();
        PhoneEntry entry = new PhoneEntry(phone, "ada", "ada-" + phone, "dashboard",
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        assertTrue(store.insert(entry));
        assertFalse(store.insert(entry), "the unique index refuses the same number twice");
        assertEquals(entry, store.find(phone).orElseThrow());
        assertTrue(store.list().contains(entry));

        PhoneEntry renamed = new PhoneEntry(phone, "grace", entry.username(), entry.role(), entry.addedAt());
        assertTrue(store.replace(renamed));
        assertEquals(renamed, store.find(phone).orElseThrow());

        assertTrue(store.delete(phone));
        assertTrue(store.find(phone).isEmpty());
        assertFalse(store.delete(phone));
    }

    @Test
    void a_second_replica_sees_the_first_replicas_write() {
        MongoPhoneRecords other = new MongoPhoneRecords(mongo, database);
        String phone = TestNumbers.fresh();
        PhoneEntry entry = new PhoneEntry(phone, "ada", "ada-" + phone, "dashboard",
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        assertTrue(store.insert(entry));
        assertFalse(other.insert(entry), "the other replica hits the same unique index");
        assertEquals(entry, other.find(phone).orElseThrow());
        assertTrue(other.delete(phone));
        assertTrue(store.find(phone).isEmpty());
    }
}
