package io.freedriver.app.api;

import io.freedriver.dashboard.api.RateLimitBucket;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.anyOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BuildResourceTest {

    private static final String PREFIX = "freedriver:rate:dashboard:";
    private static final List<String> BUCKETS = Arrays.stream(RateLimitBucket.values()).map(RateLimitBucket::key).toList();

    @Inject
    RedisDataSource redis;

    @Inject
    @ConfigProperty(name = "freedriver.appliances.rate-limit.permits")
    int permits;
    @Test
    void testBuildEndpoint() {
        given()
                .when().get("/api/build")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("build", is("1.0.0-SNAPSHOT"));
    }

    /** No rate-limit filter runs on an unmarked route: anonymous build reads past the permit count stay 200. */
    @Test
    void build_is_never_rate_limited() {
        Set<String> before = rateKeys();
        for (int i = 0; i < permits + 4; i++) {
            given().when().get("/api/build").then().statusCode(200).body("build", is("1.0.0-SNAPSHOT"));
        }
        Set<String> created = new HashSet<>(rateKeys());
        created.removeAll(before);
        assertTrue(created.stream().noneMatch(key -> BUCKETS.stream().anyMatch(b -> key.startsWith(PREFIX + b + ":"))),
                created::toString);
    }

    private Set<String> rateKeys() {
        return new HashSet<>(redis.key().keys(PREFIX + "*"));
    }

    @Test
    void anonymous_ready_is_open() {
        given().get("/q/health/live").then().statusCode(200);
        given().get("/q/health/ready").then().statusCode(anyOf(is(200), is(503)));
    }
}
