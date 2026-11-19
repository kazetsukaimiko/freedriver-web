package io.freedriver.architecture.fixtures.beanstate;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import org.jmolecules.architecture.hexagonal.SecondaryPort;

/** A repository bean that keeps a cache in this process's memory. */
@SecondaryPort
@ApplicationScoped
public class CachingRepository {

    private final ConcurrentHashMap<String, Boolean> indexed = new ConcurrentHashMap<>();

    public boolean indexed(String collection) {
        return indexed.containsKey(collection);
    }
}
