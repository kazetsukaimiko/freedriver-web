package io.freedriver.app.instances;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class HouseRepository implements PanacheMongoRepositoryBase<House, String> {

    public void save(House house) {
        house.id = lowercaseUuid(house.id);
        persist(house);
    }

    public Optional<House> load(String id) {
        return findByIdOptional(lowercaseUuid(id));
    }

    static String lowercaseUuid(String id) {
        if (id == null) {
            throw new IllegalArgumentException("house id is required");
        }
        final UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("house id must be a lowercase uuid");
        }
        String canonical = uuid.toString();
        if (!canonical.equals(id)) {
            throw new IllegalArgumentException("house id must be a lowercase uuid");
        }
        return canonical;
    }
}
