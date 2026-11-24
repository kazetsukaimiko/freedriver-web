package io.freedriver.sms;

import io.freedriver.architecture.LayeringTest;

/**
 * The Java layering rules (#560) for this module. Violations recorded in
 * src/test/resources/archunit-store pass; a new one fails with the fix in its message.
 */
class ArchitectureTest extends LayeringTest {

    @Override
    protected String exceptionPackage() {
        return "io.freedriver.sms.errors";
    }

    /** Rules 1 to 4 for sms-api. That module compiles for Java 21 and does not depend on architecture-rules. */
    @org.junit.jupiter.api.Test
    void sms_api_keeps_the_api_quality_rules() {
        io.freedriver.architecture.ApiQuality.checkModule(
                java.nio.file.Path.of("../sms-api/target/classes"),
                java.nio.file.Path.of("../sms-api/src/test/resources/archunit-store"));
    }

    /** Rules 1 to 4 for sms-model. */
    @org.junit.jupiter.api.Test
    void sms_model_keeps_the_api_quality_rules() {
        io.freedriver.architecture.ApiQuality.checkModule(
                java.nio.file.Path.of("../sms-model/target/classes"),
                java.nio.file.Path.of("../sms-model/src/test/resources/archunit-store"));
    }

    /** Rules 1 to 4 for sms-client. */
    @org.junit.jupiter.api.Test
    void sms_client_keeps_the_api_quality_rules() {
        io.freedriver.architecture.ApiQuality.checkModule(
                java.nio.file.Path.of("../sms-client/target/classes"),
                java.nio.file.Path.of("../sms-client/src/test/resources/archunit-store"));
    }
}
