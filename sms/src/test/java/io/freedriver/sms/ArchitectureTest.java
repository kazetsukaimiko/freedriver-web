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

    /** Rules 2, 3 and 4 for sms-api. That module compiles for Java 21 and does not depend on architecture-rules. */
    @org.junit.jupiter.api.Test
    void sms_api_keeps_the_contract_rules() {
        contract(java.nio.file.Path.of("../sms-api"));
    }

    /** Rules 2, 3 and 4 for sms-model. */
    @org.junit.jupiter.api.Test
    void sms_model_keeps_the_contract_rules() {
        contract(java.nio.file.Path.of("../sms-model"));
    }

    /** Rules 1, 3 and 4 for sms-client. */
    @org.junit.jupiter.api.Test
    void sms_client_keeps_the_service_rules() {
        java.nio.file.Path module = java.nio.file.Path.of("../sms-client");
        java.nio.file.Path classes = module.resolve("target/classes");
        java.nio.file.Path store = module.resolve("src/test/resources/archunit-store");
        io.freedriver.architecture.ApiQuality.check(
                io.freedriver.architecture.ApiQualityRules.serviceDependencies(),
                io.freedriver.architecture.ApiQuality.importClasses(classes), store);
        io.freedriver.architecture.ApiQuality.check(
                io.freedriver.architecture.ApiQualityRules.implementationsLiveInImpl(),
                io.freedriver.architecture.ApiQuality.importClasses(classes), store);
        io.freedriver.architecture.ApiQuality.check(
                io.freedriver.architecture.ApiQualityRules.implementationPackagesStayPrivate(),
                io.freedriver.architecture.ApiQuality.importClasses(classes), store);
    }

    private static void contract(java.nio.file.Path module) {
        java.nio.file.Path classes = module.resolve("target/classes");
        java.nio.file.Path store = module.resolve("src/test/resources/archunit-store");
        io.freedriver.architecture.ApiQuality.check(
                io.freedriver.architecture.ApiQualityRules.apiContracts(),
                io.freedriver.architecture.ApiQuality.importClasses(classes), store);
        io.freedriver.architecture.ApiQuality.check(
                io.freedriver.architecture.ApiQualityRules.implementationsLiveInImpl(),
                io.freedriver.architecture.ApiQuality.importClasses(classes), store);
        io.freedriver.architecture.ApiQuality.check(
                io.freedriver.architecture.ApiQualityRules.implementationPackagesStayPrivate(),
                io.freedriver.architecture.ApiQuality.importClasses(classes), store);
    }
}
