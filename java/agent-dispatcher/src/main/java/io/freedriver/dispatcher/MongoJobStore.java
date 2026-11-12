package io.freedriver.dispatcher;

import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;

/** Production queue. MongoDB is the only copy of jobs, hosts, and submit locks. */
@ApplicationScoped
public class MongoJobStore extends DelegatingJobStore implements JobStore {

    /** Arc builds the client proxy with this constructor. It does not serve calls. */
    MongoJobStore() {
    }

    @Inject
    MongoJobStore(
            MongoClient client,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database,
            @ConfigProperty(name = "freedriver.redis.url", defaultValue = "redis://redis:6379") String redisUrl,
            OpenBotJobPing ping) {
        super(new DocumentJobStore(new MongoJobDocuments(client, database, redisUrl), Clock.systemUTC(), ping));
    }
}
