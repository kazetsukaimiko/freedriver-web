package io.freedriver.app.phones;

import com.mongodb.client.MongoClient;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.bson.Document;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal-admin read of the {@code sign-ins} collection. Same session as {@code /api/phones}.
 */
@ApplicationScoped
@Path("/api/sign-ins")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("portal-admin")
public class SignInRecordsResource {

    private final MongoClient mongo;
    private final String database;

    @Inject
    public SignInRecordsResource(
            MongoClient mongo,
            @ConfigProperty(name = "quarkus.mongodb.database", defaultValue = "freedriver") String database) {
        this.mongo = mongo;
        this.database = database;
    }

    @GET
    public RecordList list() {
        List<RecordRow> signIns = new ArrayList<>();
        for (Document document : mongo.getDatabase(database).getCollection("sign-ins").find()) {
            signIns.add(row(document));
        }
        return new RecordList(List.copyOf(signIns));
    }

    private static RecordRow row(Document document) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            raw.put(entry.getKey(), json(entry.getValue()));
        }
        Object id = document.get("_id");
        return new RecordRow(id == null ? "" : id.toString(), text(document.get("phone")), raw);
    }

    private static Object json(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Document nested) {
            Map<String, Object> raw = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : nested.entrySet()) {
                raw.put(entry.getKey(), json(entry.getValue()));
            }
            return raw;
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    public record RecordList(List<RecordRow> signIns) {
    }

    public record RecordRow(String id, String phone, Map<String, Object> document) {
    }
}
