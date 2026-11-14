package io.freedriver.sms.phones;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/** The phone allowlist in {@code phones}. Each call reads the collection. */
@ApplicationScoped
public class PhoneRepository implements PanacheMongoRepositoryBase<Phone, String> {

    Optional<PhoneEntry> find(String phone) {
        return findByIdOptional(phone).map(Phone::entry);
    }

    List<PhoneEntry> list() {
        return mongoCollection().find().sort(Sorts.ascending("addedAt")).into(new ArrayList<>())
                .stream().map(Phone::entry).toList();
    }

    /** False when the number is already stored. */
    boolean insert(PhoneEntry entry) {
        try {
            persist(Phone.of(entry));
            return true;
        } catch (MongoWriteException ex) {
            if (ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw ex;
        }
    }

    /** False when the number is not stored. */
    boolean delete(String phone) {
        return deleteById(phone);
    }

    /**
     * One filtered update: the row must still hold {@code pending} or already hold this username.
     * False when the number is missing or holds another username.
     */
    boolean attachUsername(String phone, String pending, String username) {
        return mongoCollection().updateOne(
                and(eq("_id", phone), in("username", pending, username)),
                Updates.set("username", username)).getMatchedCount() == 1;
    }
}
