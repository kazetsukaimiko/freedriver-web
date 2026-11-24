package io.freedriver.app;

import io.freedriver.architecture.LayeringTest;

/**
 * The Java layering rules (#560) for this module. Violations recorded in
 * src/test/resources/archunit-store pass; a new one fails with the fix in its message.
 */
class ArchitectureTest extends LayeringTest {

    @Override
    protected String exceptionPackage() {
        return "io.freedriver.app.errors";
    }

    /** Rules 1 to 4 for mqtt-contract. That module stays off the mqtt-contract publish reactor. */
    @org.junit.jupiter.api.Test
    void mqtt_contract_keeps_the_api_quality_rules() {
        io.freedriver.architecture.ApiQuality.checkModule(
                java.nio.file.Path.of("../../mqtt-contract/target/classes"),
                java.nio.file.Path.of("../../mqtt-contract/src/test/resources/archunit-store"));
    }

    /** Rules 1 to 4 for freedriver-mqtt, judged from this downstream module. */
    @org.junit.jupiter.api.Test
    void freedriver_mqtt_keeps_the_api_quality_rules() {
        io.freedriver.architecture.ApiQuality.checkModule(
                java.nio.file.Path.of("../../mqtt/target/classes"),
                java.nio.file.Path.of("../../mqtt/src/test/resources/archunit-store"));
    }
}
