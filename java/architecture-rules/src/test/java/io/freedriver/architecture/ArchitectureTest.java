package io.freedriver.architecture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Rules 1 to 4 for stereotypes. The store lives in that module. architecture-rules is test support and is
 * not itself judged, and stereotypes takes no dependency on architecture-rules.
 */
class ArchitectureTest {

    @BeforeEach
    void onTheRecordedJdk() {
        ApiQuality.recordedJdk();
    }

    @Test
    void stereotypes_keep_the_api_quality_rules() {
        ApiQuality.checkModule(
                Path.of("../stereotypes/target/classes"),
                Path.of("../stereotypes/src/test/resources/archunit-store"));
    }
}
