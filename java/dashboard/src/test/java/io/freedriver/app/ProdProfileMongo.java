package io.freedriver.app;

import java.util.HashMap;
import java.util.Map;

/**
 * A test that boots with the prod config profile does not get the {@code %test} Mongo settings.
 * These overrides give it a Dev Services Mongo and its own database, as the test profile has.
 */
public final class ProdProfileMongo {

    private ProdProfileMongo() {
    }

    public static Map<String, String> with(Map<String, String> overrides) {
        Map<String, String> all = new HashMap<>(overrides);
        all.put("quarkus.mongodb.devservices.enabled", "true");
        all.put("quarkus.mongodb.devservices.image-name", "mongo:8.0");
        all.put("quarkus.mongodb.devservices.container-env.GLIBC_TUNABLES", "glibc.pthread.rseq=1");
        all.put("quarkus.mongodb.database", "freedriver-test-${quarkus.uuid}");
        return all;
    }
}
