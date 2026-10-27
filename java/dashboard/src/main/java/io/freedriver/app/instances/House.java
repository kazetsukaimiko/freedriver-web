package io.freedriver.app.instances;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * One Autonomy house in the {@code instances} collection.
 * {@code brokerSecret} is stored and is not part of {@link #toString()}.
 */
@MongoEntity(collection = "instances")
public class House {

    @BsonId
    public String id;

    public String label;

    public String autonomyUsername;

    @JsonIgnore
    public String brokerSecret;

    @Override
    public String toString() {
        return "House{id=" + id + ", label=" + label + ", autonomyUsername=" + autonomyUsername + "}";
    }
}
