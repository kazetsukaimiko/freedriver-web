package io.freedriver.architecturecheck;

import io.freedriver.architecture.ApiQuality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * The reactor-wide check (#777): no package's main classes come from two reactor modules. It reads
 * the main output of every module in the reactor, test support included. Each path is also a
 * {@code maven.build.cache.input} of this module and each module a test dependency, so the
 * reactor builds them first and a change to one reruns this test. The split recorded in
 * src/test/resources/archunit-store passes; a new one fails.
 */
class ArchitectureTest {

    private static final List<Path> MAIN_OUTPUTS = List.of(
            Path.of("../hit-window/target/classes"),
            Path.of("../hit-window-testing/target/classes"),
            Path.of("../stereotypes/target/classes"),
            Path.of("../architecture-rules/target/classes"),
            Path.of("../mcp-support/target/classes"),
            Path.of("../dashboard-model/target/classes"),
            Path.of("../dashboard-api/target/classes"),
            Path.of("../dashboard/target/classes"),
            Path.of("../agent-dispatcher-model/target/classes"),
            Path.of("../agent-dispatcher-api/target/classes"),
            Path.of("../agent-dispatcher/target/classes"),
            Path.of("../autonomy-mcp/target/classes"),
            Path.of("../device-daemon/target/classes"),
            Path.of("../../mqtt-contract/target/classes"),
            Path.of("../../mqtt/target/classes"),
            Path.of("../../sms-model/target/classes"),
            Path.of("../../sms-api/target/classes"),
            Path.of("../../sms-client/target/classes"),
            Path.of("../../sms/target/classes"));

    @BeforeEach
    void onTheRecordedJdk() {
        ApiQuality.recordedJdk();
    }

    @Test
    void a_package_comes_from_one_reactor_module() {
        ApiQuality.checkSplitPackages(MAIN_OUTPUTS);
    }
}
