package io.freedriver.architecture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Rules 3 and 4 for stereotypes. The store lives in that module. architecture-rules is test support and is
 * not itself judged, and stereotypes takes no dependency on architecture-rules.
 */
class ArchitectureTest {

    @BeforeEach
    void onTheRecordedJdk() {
        ApiQuality.recordedJdk();
    }

    @Test
    void stereotypes_keep_implementations_in_impl_modules() {
        ApiQuality.check(ApiQualityRules.implementationsLiveInImpl(),
                ApiQuality.importClasses(Path.of("../stereotypes/target/classes")),
                Path.of("../stereotypes/src/test/resources/archunit-store"));
    }

    @Test
    void stereotypes_keep_implementation_packages_private() {
        ApiQuality.check(ApiQualityRules.implementationPackagesStayPrivate(),
                ApiQuality.importClasses(Path.of("../stereotypes/target/classes")),
                Path.of("../stereotypes/src/test/resources/archunit-store"));
    }
}
