package io.freedriver.app.jobs;

import com.mongodb.client.MongoClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@TestProfile(AgentJobsResourceTest.MongoProfile.class)
class AgentJobsResourceTest {

    public static class MongoProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.mongodb.devservices.enabled", "true",
                    "quarkus.mongodb.devservices.image-name", "mongo:8.0",
                    "quarkus.mongodb.devservices.container-env.GLIBC_TUNABLES", "glibc.pthread.rseq=1");
        }
    }

    @Inject
    MongoClient mongo;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.mongodb.database")
    String database;

    @Test
    @TestSecurity(user = "jobs-admin", roles = "portal-admin")
    void a_saved_job_is_listed_with_its_document() {
        Date created = new Date(1_700_000_000_000L);
        mongo.getDatabase(database).getCollection("agent-jobs").insertOne(new Document("_id", "assignment-1")
                .append("assignmentId", "assignment-1")
                .append("jobId", "job-1")
                .append("prompt", "say hello")
                .append("channel", "TEXT")
                .append("status", "QUEUED")
                .append("note", "not yet")
                .append("createdAt", created)
                .append("updatedAt", created));

        given().when()
                .get("/api/jobs")
                .then()
                .statusCode(200)
                .body("jobs", hasSize(1))
                .body("jobs[0].prompt", equalTo("say hello"))
                .body("jobs[0].channel", equalTo("TEXT"))
                .body("jobs[0].status", equalTo("QUEUED"))
                .body("jobs[0].note", equalTo("not yet"))
                .body("jobs[0].document.prompt", equalTo("say hello"))
                .body("jobs[0].document.status", equalTo("QUEUED"))
                .body("jobs[0].document.assignmentId", equalTo("assignment-1"));
    }
}
