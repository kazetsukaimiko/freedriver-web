package io.freedriver.architecture.fixtures.beanstate;

import io.freedriver.stereotypes.ProcessLocal;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import org.jmolecules.architecture.hexagonal.SecondaryPort;

/** The same cache, declared. */
@SecondaryPort
@ApplicationScoped
public class DeclaredRepository {

    @ProcessLocal("an index exists once created; another replica creates it again harmlessly")
    private final ConcurrentHashMap<String, Boolean> indexed = new ConcurrentHashMap<>();

    public boolean indexed(String collection) {
        return indexed.containsKey(collection);
    }
}
