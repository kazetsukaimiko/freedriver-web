package io.freedriver.sms.phones;

import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Date;

import static com.mongodb.client.model.Filters.eq;

/** Last accepted code time per number. Each call reads the collection. */
@ApplicationScoped
public class SignInRepository implements PanacheMongoRepositoryBase<SignIn, String> {

    Instant get(String phone) {
        return findByIdOptional(phone).map(row -> row.acceptedAt).orElse(null);
    }

    /** One upsert. A later sign-in replaces the time. */
    void put(String phone, Instant when) {
        mongoCollection().updateOne(
                eq("_id", phone),
                Updates.combine(Updates.set("phone", phone), Updates.set("acceptedAt", Date.from(when))),
                new UpdateOptions().upsert(true));
    }

    void delete(String phone) {
        deleteById(phone);
    }
}
