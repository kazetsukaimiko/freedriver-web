package io.freedriver.app.invites;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** The unique {@code codeHash} index, created at startup before the archive import. */
@ApplicationScoped
class InviteIndexes {

    void create(@Observes @Priority(1) StartupEvent start, InviteStore invites) {
        invites.mongoCollection().createIndex(Indexes.ascending("codeHash"), new IndexOptions().unique(true));
    }
}
