package io.freedriver.app.api;

import io.freedriver.app.changelog.Changelog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static io.restassured.RestAssured.given;

/** A jar without a changelog file answers 404. */
@QuarkusTest
@TestProfile(ChangelogMissingTest.NoChangelogProfile.class)
class ChangelogMissingTest {

    public static class NoChangelogProfile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(NoChangelog.class);
        }
    }

    @Alternative
    @ApplicationScoped
    public static class NoChangelog extends Changelog {
        @Override
        public Optional<String> text() {
            return Optional.empty();
        }
    }

    @Test
    @TestSecurity(user = "scott", roles = {"dashboard"})
    void missing_changelog_is_404() {
        given().header("X-Requested-With", "JavaScript")
                .when().get("/api/changelog")
                .then()
                .statusCode(404);
    }
}
