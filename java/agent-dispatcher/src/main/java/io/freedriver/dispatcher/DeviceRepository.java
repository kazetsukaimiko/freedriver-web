package io.freedriver.dispatcher;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/** {@code agent-devices}. Each write is one command. */
@ApplicationScoped
public class DeviceRepository implements PanacheMongoRepositoryBase<Device, String> {

    /** One upsert that sets the host, its agents, their activity, and the heartbeat. */
    Device report(String deviceId, String hostname, List<String> agents,
                  Map<String, String> activity, Map<String, String> activityText, Instant now) {
        return mongoCollection().findOneAndUpdate(
                eq("_id", deviceId),
                Updates.combine(
                        Updates.set("hostname", hostname),
                        Updates.set("agents", agents),
                        Updates.set("activity", new Document(activity)),
                        Updates.set("activityText", new Document(activityText)),
                        Updates.set("lastHeartbeat", Date.from(now))),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
    }

    /** Sets the heartbeat. Null when the device is unknown. */
    Device heartbeat(String deviceId, Instant now) {
        return mongoCollection().findOneAndUpdate(
                eq("_id", deviceId),
                Updates.set("lastHeartbeat", Date.from(now)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** Devices with that hostname that serve that agent. Index {@code {hostname}}. */
    List<Device> find(String hostname, String agentName) {
        return mongoCollection().find(and(eq("hostname", hostname), eq("agents", agentName))).into(new ArrayList<>());
    }
}
