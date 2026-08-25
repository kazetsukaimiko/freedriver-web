package io.freedriver.app.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublishedBuildTest {

    @Test
    void acceptsYearMonthBuildNumber() {
        assertEquals("2026-08_r184", PublishedBuild.orNull("2026-08_r184"));
        assertEquals("2026-08_r184", PublishedBuild.orNull(" 2026-08_r184 "));
    }

    @Test
    void rejectsSnapshotAndOtherSchemes() {
        assertNull(PublishedBuild.orNull(null));
        assertNull(PublishedBuild.orNull(""));
        assertNull(PublishedBuild.orNull("1.0.0-SNAPSHOT"));
        assertNull(PublishedBuild.orNull("1.0.184"));
        assertNull(PublishedBuild.orNull("2026-8_r184"));
        assertNull(PublishedBuild.orNull("2026-08_r"));
        assertNull(PublishedBuild.orNull("v2026-08_r184"));
    }

    @Test
    void prefersMatchingImplementationVersion() {
        assertEquals("2026-08_r184", PublishedBuild.resolve(HealthResource.class, "2026-08_r184"));
        assertNull(PublishedBuild.resolve(HealthResource.class, "1.0.0-SNAPSHOT"));
    }
}
