package io.freedriver.architecture.fixtures.apiquality;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.mongodb.panache.PanacheMongoRepository;

/** A service class that names both Panache packages. */
public class ServicePanache {
    PanacheMongoRepository mongo;
    PanacheRepository orm;
}
