package io.freedriver.sms.phones;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Creates the sms indexes at startup, before the archive import. Creating one that exists is a no-op. */
@ApplicationScoped
class SmsIndexes {

    void create(@Observes @Priority(1) StartupEvent start, PhoneRepository phones, SignInRepository signIns,
                ConsentLedger consents) {
        phones.mongoCollection().createIndex(Indexes.ascending("phone"), new IndexOptions().unique(true));
        signIns.mongoCollection().createIndex(Indexes.ascending("phone"), new IndexOptions().unique(true));
        consents.mongoCollection().createIndex(Indexes.ascending("phone", "purpose"));
    }
}
