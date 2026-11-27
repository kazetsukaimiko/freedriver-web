package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passing and failing fixtures for the four API quality rules, the two role rules and the
 * split-package rule, evaluated like {@link LayeringEdgeTest}.
 * A fixture names the module a class is compiled in; the class's package gives its role, and a
 * package that names none leaves the module's artifact role. Directory names that differ from
 * artifactIds are read from the reactor poms, and a jar named slf4j-api stays third-party.
 */
class ApiQualityRulesTest {

    private static final String FIXTURES = "io.freedriver.architecture.fixtures.apiquality.";
    private static final String IMPL_FIXTURES = "io.freedriver.architecture.fixtures.implpackage.";

    @Test
    void directory_names_differ_from_artifact_ids() {
        ModuleOwnership ownership = ModuleOwnership.reactor();
        Path root = repoRoot();

        ModuleOwnership.Owned mqtt = ownership.classifyUri(
                root.resolve("mqtt/target/classes/io/freedriver/mqtt/Marker.class").toUri());
        assertEquals("freedriver-mqtt", mqtt.artifactId());
        assertEquals(ModuleOwnership.Role.UTILITY, mqtt.effectiveRole());

        ModuleOwnership.Owned sms = ownership.classifyUri(
                root.resolve("sms/target/classes/io/freedriver/sms/Marker.class").toUri());
        assertEquals("sms-server", sms.artifactId());
        assertEquals(ModuleOwnership.Role.SERVICE, sms.effectiveRole());

        ModuleOwnership.Owned contract = ownership.classifyUri(
                root.resolve("mqtt-contract/target/classes/io/freedriver/mqtt/contract/Marker.class").toUri());
        assertEquals("freedriver-mqtt-contract", contract.artifactId());
        assertEquals(ModuleOwnership.Role.API, contract.effectiveRole());
    }

    @Test
    void a_foreign_jar_named_like_an_api_fails_rule_1() throws Exception {
        Path root = Files.createTempDirectory("apiquality-slf4j");
        try {
            Path jarClasses = root.resolve("jar-classes");
            writeJava(jarClasses, "org/slf4j/Logger.java",
                    "package org.slf4j; public class Logger { public static void log(String m) {} }");
            compile(jarClasses, jarClasses, List.of());
            Path jar = root.resolve("slf4j-api-2.0.9.jar");
            jar(jarClasses, jar);

            ModuleOwnership.Owned foreign = ModuleOwnership.reactor().classifyUri(
                    URI.create("jar:" + jar.toUri() + "!/org/slf4j/Logger.class"));
            assertEquals(ModuleOwnership.Kind.THIRD_PARTY, foreign.kind());
            assertTrue(foreign.detail().contains("slf4j-api"), foreign.detail());
            ModuleOwnership.Owned inApiPackage = foreign.inPackage("io.freedriver.app.api");
            assertEquals(ModuleOwnership.Kind.THIRD_PARTY, inApiPackage.kind());
            assertEquals(Optional.empty(), inApiPackage.packageRole());
            assertNull(inApiPackage.effectiveRole());

            Path module = root.resolve("sample-service");
            Path sources = module.resolve("src");
            writeJava(sources, "io/example/UsesLogger.java",
                    "package io.example; public class UsesLogger { void m() { org.slf4j.Logger.log(\"x\"); } }");
            Path classes = module.resolve("target/classes");
            compile(sources, classes, List.of(jar));
            Files.writeString(module.resolve("pom.xml"), """
                    <project>
                      <groupId>io.freedriver</groupId>
                      <artifactId>sample-service</artifactId>
                      <version>1.0.0-SNAPSHOT</version>
                    </project>
                    """);

            JavaClasses imported = new ClassFileImporter().importLocations(List.of(Location.of(classes), Location.of(jar)));
            EvaluationResult result = ApiQualityRules.serviceDependencies().evaluate(imported);
            assertTrue(result.hasViolation(), () -> "slf4j-api passed:\n" + result.getFailureReport());
            String report = result.getFailureReport().toString();
            assertTrue(report.contains("is a third-party library"), () -> report);
            assertTrue(report.contains("org.slf4j.Logger"), () -> report);
            assertTrue(report.contains("UsesLogger"), () -> report);
            assertFalse(report.contains("slf4j-api-2.0.9"), () -> report);
        } finally {
            delete(root);
        }
    }

    @Test
    void an_annotation_member_is_ignored_until_a_field_parameter_or_body_uses_it() {
        assertPasses(ApiQualityRules.serviceDependencies(overlay(
                ours(fix("AnnotationOnly"), "sample", ModuleOwnership.Role.SERVICE))),
                "annotationonly");
        assertFails(ApiQualityRules.serviceDependencies(overlay(
                ours(fix("UsesModeField"), "sample", ModuleOwnership.Role.SERVICE))),
                "modefield", fix("UsesModeField"));
        assertFails(ApiQualityRules.serviceDependencies(overlay(
                ours(fix("UsesModeParameter"), "sample", ModuleOwnership.Role.SERVICE))),
                "modeparameter", fix("UsesModeParameter"));
        assertFails(ApiQualityRules.serviceDependencies(overlay(
                ours(fix("UsesModeBody"), "sample", ModuleOwnership.Role.SERVICE))),
                "modebody", fix("UsesModeBody"));
    }

    @Test
    void a_record_with_a_static_factory_passes_rule_2() {
        assertPasses(ApiQualityRules.apiContracts(overlay(
                ours(fix("RecordFactory"), "sample-api", ModuleOwnership.Role.API))),
                "recordpass");
    }

    @Test
    void a_constant_holder_with_an_extra_method_fails_rule_2() {
        assertFails(ApiQualityRules.apiContracts(overlay(
                ours(fix("HolderWithMethod"), "sample-api", ModuleOwnership.Role.API))),
                "holderfail", fix("HolderWithMethod"));
    }

    @Test
    void a_plain_class_a_cdi_scope_and_object_mapper_fail_rule_2() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(fix("Plain"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put(fix("ScopedApi"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put(fix("UsesMapper"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put("com.fasterxml.jackson.databind.ObjectMapper",
                ModuleOwnership.Owned.thirdParty("jackson-databind"));
        EvaluationResult result = evaluate(ApiQualityRules.apiContracts(overlay(owners)), "rule2fail");
        String report = result.getFailureReport().toString();
        assertTrue(result.hasViolation(), report);
        assertTrue(report.contains(fix("Plain")), report);
        assertTrue(report.contains(fix("ScopedApi")), report);
        assertTrue(report.contains("ObjectMapper"), report);
    }

    @Test
    void an_ordinary_interface_implemented_by_example_resource_fails_rule_3() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(fix("Ordinary"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put(fix("ExampleResource"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        assertFails(ApiQualityRules.implementationsLiveInImpl(overlay(owners)),
                "exampleresource", fix("ExampleResource"));
    }

    @Test
    void a_jax_rs_resource_and_a_generated_client_pass_rule_3() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(fix("WidgetsApi"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put(fix("WidgetsResource"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        owners.put(fix("PlainApi"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put(fix("GeneratedClient"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        owners.put(fix("InImpl"), ModuleOwnership.Owned.ours("io.freedriver", "sample-impl"));
        assertPasses(ApiQualityRules.implementationsLiveInImpl(overlay(owners)), "rule3pass");
    }

    @Test
    void a_hand_written_implementor_fails_rule_3() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(fix("HandApi"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put(fix("HandWritten"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        assertFails(ApiQualityRules.implementationsLiveInImpl(overlay(owners)),
                "handwritten", fix("HandWritten"));
    }

    @Test
    void service_code_accepts_the_allowlist_and_refuses_panache_another_service_and_an_unresolved_type() {
        Map<String, ModuleOwnership.Owned> pass = new LinkedHashMap<>();
        pass.put(fix("ServiceOk"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        pass.put(fix("Contract"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        pass.put(fix("Helper"), ModuleOwnership.Owned.ours("io.freedriver", "hit-window"));
        pass.put("io.quarkus.arc.ArcContainer", ModuleOwnership.Owned.thirdParty("quarkus-arc"));
        pass.put("io.smallrye.config.SmallRyeConfig", ModuleOwnership.Owned.thirdParty("smallrye-config"));
        pass.put("org.eclipse.microprofile.config.Config", ModuleOwnership.Owned.thirdParty("microprofile-config"));
        pass.put("javax.sql.DataSource", ModuleOwnership.Owned.thirdParty("java.sql"));
        pass.put(fix("ServiceMetrics"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        pass.put("io.micrometer.core.instrument.MeterRegistry", ModuleOwnership.Owned.thirdParty("micrometer-core"));
        assertPasses(ApiQualityRules.serviceDependencies(overlay(pass)), "servicepass");

        Map<String, ModuleOwnership.Owned> panache = new LinkedHashMap<>();
        panache.put(fix("ServicePanache"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        EvaluationResult panacheResult = evaluate(
                ApiQualityRules.serviceDependencies(overlay(panache)), "panachefail");
        String panacheReport = panacheResult.getFailureReport().toString();
        assertTrue(panacheResult.hasViolation(), panacheReport);
        assertTrue(panacheReport.contains("io.quarkus.mongodb.panache"), panacheReport);
        assertTrue(panacheReport.contains("io.quarkus.hibernate.orm.panache"), panacheReport);

        Map<String, ModuleOwnership.Owned> other = new LinkedHashMap<>();
        other.put(fix("ServiceUsesOther"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        other.put(fix("OtherService"), ModuleOwnership.Owned.ours("io.freedriver", "other-service"));
        assertFails(ApiQualityRules.serviceDependencies(overlay(other)), "otherservice", fix("ServiceUsesOther"));

        Map<String, ModuleOwnership.Owned> missing = new LinkedHashMap<>();
        missing.put(fix("ServiceUnresolved"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        missing.put(fix("Missing"), ModuleOwnership.Owned.unresolved(fix("Missing")));
        EvaluationResult missingResult = evaluate(
                ApiQualityRules.serviceDependencies(overlay(missing)), "unresolved");
        String missingReport = missingResult.getFailureReport().toString();
        assertTrue(missingResult.hasViolation(), missingReport);
        assertTrue(missingReport.contains("unresolved"), missingReport);

        assertFails(ApiQualityRules.serviceDependencies(overlay(
                ours(fix("ServiceMutiny"), "sample", ModuleOwnership.Role.SERVICE))),
                "mutiny", "io.vertx.mutiny");
    }

    @Test
    void an_http_or_mcp_edge_accepts_its_framework_and_a_plain_class_fails() {
        assertPasses(service(fix("FiltersEdge")), "filtersedge");
        assertPasses(service(fix("RouterEdge")), "routeredge");
        assertPasses(service(fix("RouteEdge")), "routeedge");
        assertPasses(service(fix("RouteFilterEdge")), "routefilteredge");
        assertPasses(service(fix("OidcEdge")), "oidcedge");
        assertPasses(service(fix("ToolEdge")), "tooledge");
        assertPasses(service(fix("ToolFilterEdge")), "toolfilteredge");

        assertFails(service(fix("PlainRouting")), "plainrouting", fix("PlainRouting"));
        assertFails(service(fix("PlainTool")), "plaintool", fix("PlainTool"));

        EvaluationResult jackson = evaluate(service(fix("EdgeUsesJackson")), "edgejackson");
        String report = jackson.getFailureReport().toString();
        assertTrue(jackson.hasViolation(), report);
        assertTrue(report.contains("ObjectMapper"), report);
        assertFalse(report.contains("RoutingContext"), report);
    }

    @Test
    void a_record_subtype_of_a_sealed_api_interface_passes_rule_2_and_rule_3() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        ModuleOwnership.Owned api = ModuleOwnership.Owned.ours("io.freedriver", "sample-api");
        owners.put(fix("Mark"), api);
        owners.put(fix("SealedCase"), api);
        ModuleOwnership ownership = overlay(owners);
        assertPasses(ApiQualityRules.apiContracts(ownership), "sealedrecord");
        assertPasses(ApiQualityRules.implementationsLiveInImpl(ownership), "sealedrecord");
    }

    @Test
    void a_plain_permitted_subtype_passes_rule_3_and_fails_rule_2() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        ModuleOwnership.Owned api = ModuleOwnership.Owned.ours("io.freedriver", "sample-api");
        owners.put(fix("Mark"), api);
        owners.put(fix("PlainPermit"), api);
        ModuleOwnership ownership = overlay(owners);
        assertPasses(ApiQualityRules.implementationsLiveInImpl(ownership), "plainpermit");
        assertFails(ApiQualityRules.apiContracts(ownership), "plainpermit", fix("PlainPermit"));
    }

    @Test
    void a_service_module_record_and_a_plain_api_implementor_fail_rule_3() {
        Map<String, ModuleOwnership.Owned> serviceRecord = new LinkedHashMap<>();
        serviceRecord.put(fix("Mark"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        serviceRecord.put(fix("ServiceRecord"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        assertFails(ApiQualityRules.implementationsLiveInImpl(overlay(serviceRecord)),
                "servicerecord", fix("ServiceRecord"));

        Map<String, ModuleOwnership.Owned> open = new LinkedHashMap<>();
        open.put(fix("OpenMark"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        open.put(fix("OpenImpl"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        assertFails(ApiQualityRules.implementationsLiveInImpl(overlay(open)), "openimpl", fix("OpenImpl"));
    }

    @Test
    void a_package_names_a_role_by_the_grammar() {
        record Case(String pkg, ModuleOwnership.Role role, String implementation) {
        }
        List<Case> cases = List.of(
                new Case("io.freedriver.app.api", ModuleOwnership.Role.API, null),
                new Case("io.freedriver.app.api.dto", ModuleOwnership.Role.API, null),
                new Case("io.freedriver.app.orders.api", ModuleOwnership.Role.API, null),
                new Case("io.freedriver.app.service", ModuleOwnership.Role.SERVICE, null),
                new Case("io.freedriver.dispatcher.jobs.service", ModuleOwnership.Role.SERVICE, null),
                new Case("io.freedriver.dispatcher.jobs.service.retry", ModuleOwnership.Role.SERVICE, null),
                new Case("io.freedriver.x.impl.mongo", ModuleOwnership.Role.IMPL, "mongo"),
                new Case("io.freedriver.x.impl.mongo.codec", ModuleOwnership.Role.IMPL, "mongo"),
                new Case("io.freedriver.x.orders.impl.redis", ModuleOwnership.Role.IMPL, "redis"),
                new Case("io.freedriver.sms.api", ModuleOwnership.Role.API, null),
                new Case("io.freedriver.sms.api.rs.ext", ModuleOwnership.Role.API, null),
                new Case("io.freedriver.sms.otp.service", ModuleOwnership.Role.SERVICE, null),
                new Case("io.freedriver.sms.impl.twilio.http", ModuleOwnership.Role.IMPL, "twilio"),
                new Case("io.freedriver.x.impl", null, null),
                new Case("io.freedriver.x.orders.impl", null, null),
                new Case("io.freedriver.a.b.c.api", null, null),
                new Case("io.freedriver.a.b.c.impl.mongo", null, null),
                new Case("io.freedriver.api", null, null),
                new Case("io.freedriver.service", null, null),
                new Case("io.freedriver", null, null),
                new Case("io.freedriver.app", null, null),
                new Case("io.freedriver.app.phones", null, null),
                new Case("io.freedriver.mqtt.contract", null, null),
                new Case("io.freedriver.dashboard.model", null, null),
                new Case("io.freedriverx.app.api", null, null),
                new Case("com.example.app.api", null, null),
                new Case("io.quarkus.arc.impl.beans", null, null));
        for (Case c : cases) {
            assertEquals(Optional.ofNullable(c.role()), ModuleOwnership.packageRole(c.pkg()), c.pkg());
            assertEquals(Optional.ofNullable(c.implementation()), ModuleOwnership.implementationName(c.pkg()), c.pkg());
        }
    }

    @Test
    void a_class_takes_its_package_role_and_falls_back_to_its_artifact_role() {
        ModuleOwnership.Owned service = ModuleOwnership.Owned.ours("io.freedriver", "dashboard");
        ModuleOwnership.Owned unmatched = service.inPackage("io.freedriver.app.phones");
        assertEquals(Optional.empty(), unmatched.packageRole());
        assertEquals(ModuleOwnership.Role.SERVICE, unmatched.effectiveRole());

        ModuleOwnership.Owned apiInService = service.inPackage("io.freedriver.app.api");
        assertEquals(Optional.of(ModuleOwnership.Role.API), apiInService.packageRole());
        assertEquals(ModuleOwnership.Role.API, apiInService.effectiveRole());
        assertEquals("dashboard", apiInService.artifactId());
        assertTrue(apiInService.sameModule(unmatched));

        for (String artifactId : List.of("dashboard-model", "sms-api", "freedriver-mqtt-contract")) {
            assertEquals(ModuleOwnership.Role.API, ModuleOwnership.Owned.ours("io.freedriver", artifactId)
                    .inPackage("io.freedriver.dashboard.model").effectiveRole(), artifactId);
        }
        assertEquals(ModuleOwnership.Role.IMPL, ModuleOwnership.Owned.ours("io.freedriver", "sample-impl")
                .inPackage("io.freedriver.sample").effectiveRole());
        assertEquals(ModuleOwnership.Role.UTILITY, ModuleOwnership.Owned.ours("io.freedriver", "hit-window")
                .inPackage("io.freedriver.ratelimit").effectiveRole());

        ModuleOwnership.Owned serviceInUtility = ModuleOwnership.Owned.ours("io.freedriver", "hit-window")
                .inPackage("io.freedriver.ratelimit.service");
        assertEquals(Optional.of(ModuleOwnership.Role.SERVICE), serviceInUtility.packageRole());
        assertEquals(ModuleOwnership.Role.SERVICE, serviceInUtility.effectiveRole());

        ModuleOwnership.Owned unresolved = ModuleOwnership.Owned.unresolved("gone").inPackage("io.freedriver.app.api");
        assertEquals(Optional.empty(), unresolved.packageRole());
        assertNull(unresolved.effectiveRole());
    }

    @Test
    void every_gated_module_runs_all_six_rules() {
        assertEquals(6, ApiQualityRules.all().size());
        List<String> tests = Stream.of(ApiQualityTest.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))
                .map(java.lang.reflect.Method::getName).sorted().toList();
        assertEquals(List.of("api_code_is_a_contract", "every_class_has_a_package_role",
                "implementation_packages_stay_private", "implementations_are_implementation_code",
                "service_code_depends_on_the_allowlist", "the_module_holds_one_role"), tests);
        assertEquals(ApiQualityTest.class, LayeringTest.class.getSuperclass());
    }

    /**
     * A service module on disk, judged by the rules its test runs. The class in its API package is
     * a rule 2 subject, and the class in a package that names no role stays a rule 1 subject.
     */
    @Test
    void a_service_module_judges_its_api_package_by_rule_2_and_its_other_code_by_rule_1() throws Exception {
        Path root = Files.createTempDirectory("apiquality-roles");
        try {
            Path jarClasses = root.resolve("jar-classes");
            writeJava(jarClasses, "org/library/Tool.java",
                    "package org.library; public class Tool { public static void use() {} }");
            compile(jarClasses, jarClasses, List.of());
            Path jar = root.resolve("library-1.0.jar");
            jar(jarClasses, jar);

            Path module = root.resolve("sample-service");
            Path sources = module.resolve("src");
            writeJava(sources, "io/freedriver/samplesvc/api/NewPlain.java",
                    "package io.freedriver.samplesvc.api; public class NewPlain { public int n() { return 1; } }");
            writeJava(sources, "io/freedriver/samplesvc/api/NewShape.java",
                    "package io.freedriver.samplesvc.api; public record NewShape(String name) {}");
            writeJava(sources, "io/freedriver/samplesvc/orders/Unmatched.java",
                    "package io.freedriver.samplesvc.orders; public class Unmatched { void m() { org.library.Tool.use(); } }");
            Path classes = module.resolve("target/classes");
            compile(sources, classes, List.of(jar));
            Files.writeString(module.resolve("pom.xml"), """
                    <project>
                      <groupId>io.freedriver</groupId>
                      <artifactId>sample-service</artifactId>
                      <version>1.0.0-SNAPSHOT</version>
                    </project>
                    """);

            JavaClasses imported = new ClassFileImporter().importLocations(List.of(
                    Location.of(classes), Location.of(URI.create("jar:" + jar.toUri() + "!/"))));
            List<ArchRule> rules = ApiQualityRules.all();
            String rule1 = rules.get(0).evaluate(imported).getFailureReport().toString();
            String rule2 = rules.get(1).evaluate(imported).getFailureReport().toString();

            assertTrue(rule2.contains("io.freedriver.samplesvc.api.NewPlain is a plain class"), rule2);
            assertFalse(rule2.contains("NewShape"), rule2);
            assertFalse(rule2.contains("Unmatched"), rule2);

            assertTrue(rule1.contains("io.freedriver.samplesvc.orders.Unmatched depends on org.library.Tool "
                    + "is a third-party library"), rule1);
            assertFalse(rule1.contains("NewPlain"), rule1);
        } finally {
            delete(root);
        }
    }

    @Test
    void an_api_package_class_inside_a_service_artifact_is_judged_by_rule_2_and_not_by_rule_1() {
        ModuleOwnership ownership = roles(Map.of(role("api.PlainThing"), "sample"));
        assertFailsIn(ApiQualityRules.apiContracts(ownership), role("api.PlainThing") + " is a plain class");
        assertPassesIn(ApiQualityRules.serviceDependencies(ownership));
    }

    @Test
    void service_code_uses_its_own_service_code_and_api_packages_of_any_module() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(role("service.Worker"), "sample");
        owners.put(role("service.Helper"), "sample");
        owners.put(role("api.Port"), "sample");
        owners.put(other("api.RemoteContract"), "other-service");
        assertPassesIn(ApiQualityRules.serviceDependencies(roles(owners)));
    }

    @Test
    void service_code_that_uses_another_modules_service_package_fails_rule_1() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(role("service.UsesRemote"), "sample");
        owners.put(other("service.Remote"), "other-service");
        assertFailsIn(ApiQualityRules.serviceDependencies(roles(owners)),
                role("service.UsesRemote") + " depends on " + other("service.Remote") + " is other-service");
    }

    @Test
    void a_class_with_no_package_role_in_a_service_artifact_is_still_judged_by_rule_1() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(role("plain.Loose"), "sample");
        owners.put(other("service.Remote"), "other-service");
        assertFailsIn(ApiQualityRules.serviceDependencies(roles(owners)),
                role("plain.Loose") + " depends on " + other("service.Remote") + " is other-service");
    }

    @Test
    void service_code_that_uses_an_implementation_of_its_own_module_fails_rule_1() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(role("service.UsesImpl"), "sample");
        owners.put(role("impl.mongo.MongoPort"), "sample");
        assertFailsIn(ApiQualityRules.serviceDependencies(roles(owners)),
                role("service.UsesImpl") + " depends on " + role("impl.mongo.MongoPort")
                        + " is implementation code of its own module");
    }

    @Test
    void an_api_class_uses_api_classes_of_its_module_and_never_its_service_code() {
        Map<String, String> pass = new LinkedHashMap<>();
        pass.put(role("api.UsesApi"), "sample");
        pass.put(role("api.Port"), "sample");
        assertPassesIn(ApiQualityRules.apiContracts(roles(pass)));

        Map<String, String> fail = new LinkedHashMap<>();
        fail.put(role("api.UsesService"), "sample");
        fail.put(role("service.Helper"), "sample");
        assertFailsIn(ApiQualityRules.apiContracts(roles(fail)),
                role("api.UsesService") + " depends on " + role("service.Helper")
                        + " is service code of its own module");
    }

    @Test
    void an_implementor_in_an_impl_package_passes_rule_3_and_one_in_a_service_package_fails() {
        Map<String, String> pass = new LinkedHashMap<>();
        pass.put(role("api.Port"), "sample");
        pass.put(role("impl.mongo.MongoPort"), "sample");
        assertPassesIn(ApiQualityRules.implementationsLiveInImpl(roles(pass)));

        Map<String, String> fail = new LinkedHashMap<>();
        fail.put(role("api.Port"), "sample");
        fail.put(role("service.HandPort"), "sample");
        assertFailsIn(ApiQualityRules.implementationsLiveInImpl(roles(fail)),
                role("service.HandPort") + " implements " + role("api.Port"));
    }

    @Test
    void a_contract_subtype_below_an_api_package_passes_rule_3_in_a_service_artifact() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(role("api.Port"), "sample");
        owners.put(role("api.dto.Shape"), "sample");
        ModuleOwnership ownership = roles(owners);
        assertPassesIn(ApiQualityRules.implementationsLiveInImpl(ownership));
        assertPassesIn(ApiQualityRules.apiContracts(ownership));
    }

    @Test
    void a_class_in_a_role_package_or_a_utility_module_has_a_role() {
        assertPassesIn(ApiQualityRules.classesHaveAPackageRole(roles(Map.of(
                role("service.Helper"), "sample",
                role("api.Port"), "sample",
                role("impl.mongo.MongoPort"), "sample",
                role("plain.Loose"), "hit-window"))));
    }

    @Test
    void an_unmatched_package_in_a_service_artifact_fails_the_role_check_while_rule_1_judges_it() {
        Map<String, String> owners = new LinkedHashMap<>();
        owners.put(role("plain.Loose"), "sample");
        owners.put(other("service.Remote"), "other-service");
        ModuleOwnership ownership = roles(owners);
        assertFailsIn(ApiQualityRules.classesHaveAPackageRole(ownership),
                role("plain.Loose") + " is in package io.freedriver.rolefixture.plain, which names no role");
        assertFailsIn(ApiQualityRules.serviceDependencies(ownership),
                role("plain.Loose") + " depends on " + other("service.Remote") + " is other-service");
    }

    @Test
    void an_unmatched_package_in_an_api_artifact_fails_the_role_check() {
        assertFailsIn(ApiQualityRules.classesHaveAPackageRole(roles(Map.of(role("plain.Loose"), "sample-api"))),
                role("plain.Loose") + " is in package io.freedriver.rolefixture.plain, which names no role");
    }

    @Test
    void a_module_whose_classes_share_one_role_passes() {
        assertPassesIn(ApiQualityRules.modulesHoldOneRole(roles(Map.of(
                role("service.Worker"), "sample",
                role("service.Helper"), "sample",
                role("plain.Loose"), "sample",
                other("api.RemoteContract"), "other-api"))));
    }

    @Test
    void a_module_with_two_roles_fails_and_names_each_class_outside_its_main_role() {
        assertFailsIn(ApiQualityRules.modulesHoldOneRole(roles(Map.of(
                role("service.Worker"), "sample",
                role("service.Helper"), "sample",
                role("api.PlainThing"), "sample"))),
                role("api.PlainThing") + " is API code in module sample, where most classes are service code");
    }

    @Test
    void a_role_package_inside_a_utility_module_fails_one_role_per_module() {
        assertFailsIn(ApiQualityRules.modulesHoldOneRole(roles(Map.of(
                role("plain.Loose"), "hit-window",
                role("plain.Spare"), "hit-window",
                role("service.Helper"), "hit-window"))),
                role("service.Helper") + " is service code in module hit-window, where most classes are utility code");
    }

    /** The store compares lines, so the added class has to bring a line of its own. */
    @Test
    void an_implementation_added_to_an_already_mixed_module_is_a_new_violation_that_names_it() {
        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put(role("service.Worker"), "sample");
        mixed.put(role("service.Helper"), "sample");
        mixed.put(role("service.HandPort"), "sample");
        mixed.put(role("api.PlainThing"), "sample");
        List<String> frozen = lines(ApiQualityRules.modulesHoldOneRole(roles(mixed)));
        assertEquals(List.of(role("api.PlainThing")
                + " is API code in module sample, where most classes are service code"), frozen);

        mixed.put(role("impl.mongo.MongoPort"), "sample");
        List<String> after = new ArrayList<>(lines(ApiQualityRules.modulesHoldOneRole(roles(mixed))));
        assertTrue(after.containsAll(frozen), after::toString);
        after.removeAll(frozen);
        assertEquals(List.of(role("impl.mongo.MongoPort")
                + " is implementation code in module sample, where most classes are service code"), after);
    }

    private static List<String> lines(ArchRule rule) {
        return evaluateRoles(rule).getFailureReport().getDetails().stream().sorted().toList();
    }

    @Test
    void a_package_split_across_two_modules_fails() throws Exception {
        Path root = Files.createTempDirectory("apiquality-split");
        try {
            Path a = module(root, "split-a", "io/freedriver/splitfix/shared/Left.java",
                    "package io.freedriver.splitfix.shared; public class Left {}");
            Path b = module(root, "split-b", "io/freedriver/splitfix/shared/Right.java",
                    "package io.freedriver.splitfix.shared; public class Right {}");
            List<String> lines = split(a.resolve("target/classes"), b.resolve("target/classes"));
            assertEquals(List.of(
                    "io.freedriver.splitfix.shared.Left is in package io.freedriver.splitfix.shared, "
                            + "whose main classes come from reactor modules split-a and split-b",
                    "io.freedriver.splitfix.shared.Right is in package io.freedriver.splitfix.shared, "
                            + "whose main classes come from reactor modules split-a and split-b"), lines);
        } finally {
            delete(root);
        }
    }

    @Test
    void two_modules_with_their_own_packages_pass_the_split_check() throws Exception {
        Path root = Files.createTempDirectory("apiquality-split");
        try {
            Path a = module(root, "own-a", "io/freedriver/splitfix/a/Left.java",
                    "package io.freedriver.splitfix.a; public class Left {}");
            Path b = module(root, "own-b", "io/freedriver/splitfix/b/Right.java",
                    "package io.freedriver.splitfix.b; public class Right {}");
            assertEquals(List.of(), split(a.resolve("target/classes"), b.resolve("target/classes")));
        } finally {
            delete(root);
        }
    }

    @Test
    void a_modules_classes_directory_and_its_own_jar_are_one_owner() throws Exception {
        Path root = Files.createTempDirectory("apiquality-split");
        try {
            Path a = module(root, "jarred-a", "io/freedriver/splitfix/jarred/Left.java",
                    "package io.freedriver.splitfix.jarred; public class Left {}");
            Path jar = a.resolve("target/jarred-a-1.0.0-SNAPSHOT.jar");
            jar(a.resolve("target/classes"), jar);
            assertEquals(List.of(), split(a.resolve("target/classes"), jar));
        } finally {
            delete(root);
        }
    }

    @Test
    void one_class_name_compiled_in_two_modules_fails() throws Exception {
        Path root = Files.createTempDirectory("apiquality-split");
        try {
            String source = "package io.freedriver.splitfix.twice; public class Same {}";
            Path a = module(root, "twice-a", "io/freedriver/splitfix/twice/Same.java", source);
            Path b = module(root, "twice-b", "io/freedriver/splitfix/twice/Same.java", source);
            List<String> lines = split(a.resolve("target/classes"), b.resolve("target/classes"));
            assertTrue(lines.contains("io.freedriver.splitfix.twice.Same is compiled in reactor modules "
                    + "twice-a and twice-b"), lines::toString);
        } finally {
            delete(root);
        }
    }

    @Test
    void an_output_of_no_reactor_module_is_refused() throws Exception {
        Path root = Files.createTempDirectory("apiquality-split");
        try {
            Path classes = root.resolve("stray/target/classes");
            Files.createDirectories(classes);
            assertThrows(IllegalArgumentException.class, () -> ReactorOutputs.of(List.of(classes)));
            assertThrows(IllegalArgumentException.class,
                    () -> ReactorOutputs.of(List.of(root.resolve("gone/target/classes"))));
        } finally {
            delete(root);
        }
    }

    /** A module of ours on disk: a pom and one compiled class under target/classes. */
    private static Path module(Path root, String artifactId, String relative, String source) throws IOException {
        Path module = root.resolve(artifactId);
        Path sources = module.resolve("src");
        writeJava(sources, relative, source);
        compile(sources, module.resolve("target/classes"), List.of());
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <groupId>io.freedriver</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0-SNAPSHOT</version>
                </project>
                """.formatted(artifactId));
        return module;
    }

    private static List<String> split(Path... outputs) {
        ReactorOutputs reactor = ReactorOutputs.of(List.of(outputs));
        return reactor.packagesComeFromOneModule().evaluate(reactor.classes())
                .getFailureReport().getDetails().stream().sorted().toList();
    }

    private static String role(String relative) {
        return "io.freedriver.rolefixture." + relative;
    }

    private static String other(String relative) {
        return "io.freedriver.otherfixture." + relative;
    }

    /** Class name to artifactId. The classes sit in role packages, so the package gives each its role. */
    private static ModuleOwnership roles(Map<String, String> artifactIds) {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        artifactIds.forEach((name, artifactId) -> owners.put(name, ModuleOwnership.Owned.ours("io.freedriver", artifactId)));
        return overlay(owners);
    }

    private static void assertPassesIn(ArchRule rule) {
        EvaluationResult result = evaluateRoles(rule);
        assertFalse(result.hasViolation(), () -> result.getFailureReport().toString());
    }

    private static void assertFailsIn(ArchRule rule, String line) {
        EvaluationResult result = evaluateRoles(rule);
        String report = result.getFailureReport().toString();
        assertTrue(result.hasViolation(), () -> "passed, expected: " + line);
        assertTrue(report.contains(line), () -> "expected \"" + line + "\" in:\n" + report);
    }

    /** The overlaid classes are the subjects: every other fixture is a test class and stays unjudged. */
    private static EvaluationResult evaluateRoles(ArchRule rule) {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("io.freedriver.rolefixture", "io.freedriver.otherfixture");
        assertTrue(classes.stream().anyMatch(type -> type.getName().equals(role("api.Port"))),
                "imported no role fixture");
        return rule.evaluate(classes);
    }

    private static ArchRule service(String name) {
        return ApiQualityRules.serviceDependencies(overlay(
                ours(name, "sample", ModuleOwnership.Role.SERVICE)));
    }

    @Test
    void a_service_class_that_depends_on_an_implementation_fails_rule_4() {
        assertFailsRule4("sample", impl("service.Wires"), "from outside it");
    }

    @Test
    void an_api_class_that_depends_on_an_implementation_fails_rule_4() {
        assertFailsRule4("sample-api", impl("api.Leaky"), "from outside it");
    }

    @Test
    void an_implementation_that_depends_on_service_code_fails_rule_4() {
        assertFailsRule4("sample", impl("impl.mongo.UsesService"), "in a service package");
    }

    @Test
    void an_implementation_that_depends_on_another_implementation_fails_rule_4() {
        assertFailsRule4("sample", impl("impl.redis.RedisPort"),
                "from implementation " + IMPL_FIXTURES + "impl.redis");
    }

    @Test
    void an_implementation_that_uses_its_own_api_and_its_own_classes_passes_rule_4() {
        assertPassesRule4("sample", impl("impl.mongo.MongoPort"));
        assertPassesRule4("sample", impl("impl.mongo.sub.Deep"));
    }

    @Test
    void a_service_class_that_depends_on_an_api_passes_rule_4() {
        assertPassesRule4("sample", impl("service.Orders"));
    }

    @Test
    void a_class_directly_in_impl_is_in_no_implementation_for_rule_4() {
        assertEquals(IMPL_FIXTURES + "impl.mongo", ApiQualityRules.implementation(IMPL_FIXTURES + "impl.mongo.sub"));
        assertNull(ApiQualityRules.implementation(IMPL_FIXTURES + "impl"));
        assertPassesRule4("sample", impl("impl.Bare"));
        assertPassesRule4("sample", impl("service.UsesBare"));
    }

    @Test
    void a_library_impl_package_is_outside_rule_4() {
        assertNull(ApiQualityRules.implementation("io.quarkus.arc.impl.beans"));
        assertNull(ApiQualityRules.implementation("com.fasterxml.jackson.databind.ser.impl"));
        assertPassesRule4("sample", impl("service.UsesLibraryImpl"));
    }

    @Test
    void a_test_class_is_outside_rule_4() {
        EvaluationResult result = evaluateRule4(ModuleOwnership.reactor());
        assertFalse(result.hasViolation(), () -> result.getFailureReport().toString());
    }

    private static String impl(String relative) {
        return IMPL_FIXTURES + relative;
    }

    private static void assertPassesRule4(String artifactId, String subject) {
        EvaluationResult result = evaluateRule4(overlay(Map.of(subject,
                ModuleOwnership.Owned.ours("io.freedriver", artifactId))));
        assertFalse(result.hasViolation(), () -> subject + ": " + result.getFailureReport());
    }

    private static void assertFailsRule4(String artifactId, String subject, String why) {
        EvaluationResult result = evaluateRule4(overlay(Map.of(subject,
                ModuleOwnership.Owned.ours("io.freedriver", artifactId))));
        String report = result.getFailureReport().toString();
        assertTrue(result.hasViolation(), () -> subject + " passed:\n" + report);
        assertTrue(report.contains(subject + " depends on "), () -> "expected subject " + subject + " in:\n" + report);
        assertTrue(report.contains(why), () -> "expected \"" + why + "\" in:\n" + report);
    }

    /** The overlaid class is the one subject: every other fixture is a test class and stays unjudged. */
    private static EvaluationResult evaluateRule4(ModuleOwnership ownership) {
        String pkg = IMPL_FIXTURES.substring(0, IMPL_FIXTURES.length() - 1);
        JavaClasses classes = new ClassFileImporter().importPackages(pkg);
        assertTrue(classes.stream().anyMatch(type -> type.getPackageName().startsWith(pkg)),
                () -> "imported no class from " + pkg);
        return ApiQualityRules.implementationPackagesStayPrivate(ownership).evaluate(classes);
    }

    private static final String JSON_NODE = "com.fasterxml.jackson.databind.JsonNode is a third-party library";

    @Test
    void a_refused_type_inside_an_anonymous_class_fails_rule_1_on_the_holding_class() {
        assertAnonDepends("AnonRefused");
        assertAnonDepends("AnonNested");
        assertAnonDepends("AnonInLambda");
        assertAnonDepends("AnonField");
        assertPasses(service(fix("AnonAllowed")), "anonallowed");
        assertEquals(1, countDepends(reportOf(serviceFixture("AnonTwice"), "anontwice")), "one line for one pair");
        assertTrue(reportOf(serviceFixture("AnonTwice"), "anontwice").contains(fix("AnonTwice") + " depends on " + JSON_NODE));
    }

    @Test
    void an_anonymous_class_inside_a_member_or_local_class_is_reported_on_that_class() {
        String member = fix("AnonMember") + "$Inner";
        String memberReport = reportOf(serviceOwners(member), "anonmember");
        assertTrue(memberReport.contains(member + " depends on " + JSON_NODE), memberReport);
        assertFalse(memberReport.contains(member + "$"), memberReport);

        String local = fix("AnonLocal") + "$1Holder";
        String localReport = reportOf(serviceOwners(local), "anonlocal");
        assertTrue(localReport.contains(local + " depends on " + JSON_NODE), localReport);
        assertFalse(localReport.contains(local + "$"), localReport);
    }

    @Test
    void an_annotation_on_an_anonymous_class_is_ignored_and_a_body_use_is_not() {
        assertPasses(service(fix("AnonMarked")), "anonmarked");
        assertFails(service(fix("AnonMode")), "anonmode", fix("AnonMode"));
    }

    @Test
    void edge_allowances_follow_the_holding_class_when_the_use_is_inside_an_anonymous_class() {
        Map<String, ModuleOwnership.Owned> plainOidcOwners = new LinkedHashMap<>();
        plainOidcOwners.put(fix("PlainAnonOidc"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        plainOidcOwners.put("io.vertx.ext.web.RoutingContext", ModuleOwnership.Owned.thirdParty("vertx-web"));
        String plainOidc = reportOf(plainOidcOwners, "plainanonoidc");
        assertTrue(plainOidc.contains(fix("PlainAnonOidc")
                + " depends on io.vertx.ext.web.RoutingContext is a third-party library"), plainOidc);
        assertFalse(plainOidc.contains("$1"), plainOidc);
        assertPasses(service(fix("OidcAnonBody")), "oidcanonbody");

        Map<String, ModuleOwnership.Owned> plainToolOwners = new LinkedHashMap<>();
        plainToolOwners.put(fix("PlainAnonTool"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        plainToolOwners.put("io.quarkiverse.mcp.server.ToolResponse",
                ModuleOwnership.Owned.thirdParty("quarkus-mcp-server"));
        String plainTool = reportOf(plainToolOwners, "plainanontool");
        assertTrue(plainTool.contains(fix("PlainAnonTool")
                + " depends on io.quarkiverse.mcp.server.ToolResponse is a third-party library"), plainTool);
        assertFalse(plainTool.contains("$1"), plainTool);
        assertPasses(service(fix("ToolAnonBody")), "toolanonbody");
    }

    @Test
    void an_anonymous_class_implementing_an_api_fails_rule_3_on_the_holding_class() {
        JavaClasses classes = new ClassFileImporter().importPackages("io.freedriver.architecture.fixtures.apiquality");
        JavaClass anonymous = classes.stream()
                .filter(type -> type.isAnonymousClass() && type.getName().startsWith(fix("AnonImplements")))
                .findFirst()
                .orElseThrow();
        assertFalse(ApiQualityRules.judged(anonymous, ModuleOwnership.reactor()));
        String line = implementsOrdinary(fix("AnonImplements"));
        assertEquals(List.of(line), rule3Lines(ordinaryOwners(fix("AnonImplements")), "anonimplements"));
        assertFalse(rule3Report(ordinaryOwners(fix("AnonImplements")), "anonimplements").matches("(?s).*\\$\\d.*"));
    }

    @Test
    void an_anonymous_api_inside_a_member_or_local_class_is_reported_on_that_class() {
        String member = fix("AnonImplMember") + "$Inner";
        assertEquals(List.of(implementsOrdinary(member)), rule3Lines(ordinaryOwners(member), "anonimplmember"));
        assertFalse(rule3Report(ordinaryOwners(member), "anonimplmember").contains(member + "$"));

        String local = fix("AnonImplLocal") + "$1Holder";
        assertEquals(List.of(implementsOrdinary(local)), rule3Lines(ordinaryOwners(local), "anonimpllocal"));
        assertFalse(rule3Report(ordinaryOwners(local), "anonimpllocal").contains(local + "$"));
    }

    @Test
    void one_holding_class_and_one_api_interface_give_one_rule_3_line() {
        assertEquals(List.of(implementsOrdinary(fix("AnonImplNested"))),
                rule3Lines(ordinaryOwners(fix("AnonImplNested")), "anonimplnested"));
        assertFalse(rule3Report(ordinaryOwners(fix("AnonImplNested")), "anonimplnested").matches("(?s).*\\$\\d.*"));

        assertEquals(List.of(implementsOrdinary(fix("AnonImplPlaces"))),
                rule3Lines(ordinaryOwners(fix("AnonImplPlaces")), "anonimplplaces"));
        assertEquals(List.of(implementsOrdinary(fix("AnonImplAgain"))),
                rule3Lines(ordinaryOwners(fix("AnonImplAgain")), "anonimplagain"));
        assertEquals(List.of(implementsOrdinary(fix("AnonImplEnum"))),
                rule3Lines(ordinaryOwners(fix("AnonImplEnum")), "anonimplenum"));
        assertFalse(rule3Report(ordinaryOwners(fix("AnonImplEnum")), "anonimplenum").contains("$"));

        Map<String, ModuleOwnership.Owned> base = ordinaryOwners(fix("AnonImplBase"));
        base.put(fix("NamedImpl"), ModuleOwnership.Owned.ours("io.freedriver", "sample-impl"));
        assertEquals(List.of(implementsOrdinary(fix("AnonImplBase"))), rule3Lines(base, "anonimplbase"));
    }

    @Test
    void an_anonymous_api_passes_rule_3_in_an_implementation_package_and_on_a_jax_rs_resource() {
        Map<String, String> impl = new LinkedHashMap<>();
        impl.put(role("api.Port"), "sample");
        impl.put(role("impl.mongo.AnonPort"), "sample");
        assertPassesIn(ApiQualityRules.implementationsLiveInImpl(roles(impl)));

        Map<String, ModuleOwnership.Owned> resource = new LinkedHashMap<>();
        resource.put(fix("WidgetsApi"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        resource.put(fix("AnonWidgetsResource"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        assertEquals(List.of(), rule3Lines(resource, "anonwidgetsresource"));
    }

    @Test
    void a_jax_rs_resource_holding_an_anonymous_non_jax_rs_api_fails_rule_3() {
        assertEquals(List.of(implementsOrdinary(fix("AnonOrdinaryResource"))),
                rule3Lines(ordinaryOwners(fix("AnonOrdinaryResource")), "anonordinaryresource"));
    }

    @Test
    void a_refused_type_inside_an_anonymous_class_fails_rule_2_on_the_holding_class() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(fix("AnonMapper"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        owners.put("com.fasterxml.jackson.databind.JsonNode", ModuleOwnership.Owned.thirdParty("jackson-databind"));
        String report = evaluate(ApiQualityRules.apiContracts(overlay(owners)), "anonmapper")
                .getFailureReport().toString();
        assertEquals(1, countDepends(report), report);
        assertTrue(report.contains(fix("AnonMapper") + " depends on " + JSON_NODE), report);
        assertFalse(report.contains("$1"), report);
        assertPasses(api("AnonValue"), "anonvalue");
    }

    @Test
    void an_anonymous_class_that_opens_a_file_fails_rule_2_on_the_holding_member() {
        assertValue("OpenAnon", "method text calls java.nio.file.Files.readString");
        assertValue("OpenAnonNested", "method text calls java.nio.file.Files.readString");
        assertValue("OpenAnonStatic", "static initializer calls java.nio.file.Files.readString");
        assertValue("OpenAnonLambda", "method text calls java.nio.file.Files.readString");
        EvaluationResult anon = evaluate(api("OpenAnon"), "openanon");
        String anonReport = anon.getFailureReport().toString();
        assertFalse(anonReport.contains("$1"), anonReport);
        assertFalse(anonReport.contains("lambda$"), anonReport);
        assertFalse(anonReport.matches("(?s).*\\([^()\\s]+\\.java:\\d+\\).*"), anonReport);
        EvaluationResult enumerated = evaluate(api("OpenAnonEnum"), "openanonenum");
        String enumReport = enumerated.getFailureReport().toString();
        assertTrue(enumReport.contains("calls java.nio.file.Files.readString"), enumReport);
        assertTrue(enumReport.contains("constructor("), enumReport);
        assertFalse(enumReport.contains("$1"), enumReport);
    }

    @Test
    void a_refused_type_inside_an_anonymous_class_fails_rule_4_on_the_holding_class() {
        assertAnonRule4("sample", impl("service.AnonWires"), "from outside it");
        assertAnonRule4("sample", impl("impl.mongo.AnonUsesService"), "in a service package");
        assertAnonRule4("sample", impl("impl.mongo.AnonUsesRedis"),
                "in implementation " + IMPL_FIXTURES + "impl.redis from implementation " + IMPL_FIXTURES + "impl.mongo");
        assertPassesRule4("sample", impl("service.AnonOrders"));
        assertPassesRule4("sample", impl("impl.mongo.AnonUsesOwn"));
    }

    private static void assertAnonDepends(String simple) {
        String report = reportOf(serviceFixture(simple), simple.toLowerCase());
        assertEquals(1, countDepends(report), report);
        assertTrue(report.contains(fix(simple) + " depends on " + JSON_NODE), report);
        assertFalse(report.contains("$1"), report);
    }

    private static String reportOf(Map<String, ModuleOwnership.Owned> owners, String fixture) {
        return evaluate(ApiQualityRules.serviceDependencies(overlay(owners)), fixture).getFailureReport().toString();
    }

    private static Map<String, ModuleOwnership.Owned> serviceFixture(String simple) {
        return serviceOwners(fix(simple));
    }

    private static Map<String, ModuleOwnership.Owned> serviceOwners(String name) {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(name, ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        owners.put("com.fasterxml.jackson.databind.JsonNode", ModuleOwnership.Owned.thirdParty("jackson-databind"));
        return owners;
    }

    private static void assertAnonRule4(String artifactId, String subject, String why) {
        EvaluationResult result = evaluateRule4(overlay(Map.of(subject,
                ModuleOwnership.Owned.ours("io.freedriver", artifactId))));
        String report = result.getFailureReport().toString();
        assertTrue(result.hasViolation(), () -> subject + " passed:\n" + report);
        assertTrue(report.contains(subject + " depends on "), () -> report);
        assertTrue(report.contains(why), () -> "expected \"" + why + "\" in:\n" + report);
        assertFalse(report.contains("$1"), () -> report);
    }

    @Test
    void rule_2_accepts_values() {
        assertPasses(api("RecordWithers"), "recordwithers");
        assertPasses(api("OrdinaryEnum"), "ordinaryenum");
        assertPasses(api("ValueLambda"), "valuelambda");
        assertPasses(api("ResponseApi"), "responseapi");
        assertPasses(api("MappedConstant"), "mappedconstant");
        assertPasses(api("VarargsEnum"), "varargsenum");
        assertPasses(api("ElevenList"), "elevenlist");
        assertPasses(api("SchemeValue"), "schemevalue");
        assertPasses(api("Copied"), "copied");
        assertPasses(api("LocalSet"), "localset");
        Map<String, ModuleOwnership.Owned> sealed = new LinkedHashMap<>();
        ModuleOwnership.Owned sample = ModuleOwnership.Owned.ours("io.freedriver", "sample-api");
        sealed.put(fix("Item"), sample);
        sealed.put(fix("ItemOne"), sample);
        sealed.put(fix("ItemBox"), sample);
        assertPasses(ApiQualityRules.apiContracts(overlay(sealed)), "itembox");
    }

    @Test
    void an_ordinary_enum_has_no_line_for_compiler_generated_members() {
        EvaluationResult result = evaluate(api("OrdinaryEnum"), "ordinaryenum");
        String report = violations(result.getFailureReport().toString());
        assertFalse(result.hasViolation(), result.getFailureReport().toString());
        assertFalse(report.contains("$VALUES"), report);
        assertFalse(report.contains("method values"), report);
        assertFalse(report.contains("method valueOf"), report);
    }

    @Test
    void rule_2_refuses_mutable_statics_and_value_code() {
        assertValue("StaticArray", "field LABELS is a static array");
        assertValue("Held", "static initializer constructs java.util.ArrayList",
                "constructor(java.util.List) constructs java.util.ArrayList");
        assertValue("Handles", "method find calls java.lang.invoke.MethodHandles.lookup");
        assertValue("Fetch", "method load calls jakarta.ws.rs.client.ClientBuilder.newClient");
        assertValue("RestFetch", "method build calls org.eclipse.microprofile.rest.client.RestClientBuilder.newBuilder");
        assertValue("ConcurrentConstant", "static initializer constructs java.util.concurrent.ConcurrentHashMap");
        assertValue("MutableStatics", "static initializer constructs java.util.HashMap",
                "static initializer constructs java.util.ArrayList");
        assertValue("CompiledPattern", "static initializer calls java.util.regex.Pattern.compile");
        assertValue("LooseEnum", "field n is not final");
        Map<String, ModuleOwnership.Owned> caller = new LinkedHashMap<>();
        ModuleOwnership.Owned sample = ModuleOwnership.Owned.ours("io.freedriver", "sample-api");
        caller.put(fix("Caller"), sample);
        caller.put(fix("OtherApi"), sample);
        EvaluationResult called = evaluate(ApiQualityRules.apiContracts(overlay(caller)), "caller");
        String calledReport = called.getFailureReport().toString();
        assertTrue(called.hasViolation(), calledReport);
        assertTrue(calledReport.contains("method go calls non-sealed API interface " + fix("OtherApi")), calledReport);
        assertValue("Loud", "constructor(java.lang.String) calls java.util.logging.Logger.getLogger");
        assertValue("ReadFile", "method text calls java.nio.file.Files.readString");
        assertValue("Reflects", "method find calls java.lang.Class.forName",
                "method find calls java.lang.Class.getDeclaredMethod");
        assertValue("Loaded", "method load calls java.util.ServiceLoader.load");
        EvaluationResult lambda = evaluate(api("OpenLambda"), "openlambda");
        String lambdaReport = lambda.getFailureReport().toString();
        assertTrue(lambda.hasViolation(), lambdaReport);
        assertTrue(lambdaReport.contains("method text calls java.nio.file.Files.readString"), lambdaReport);
        assertFalse(lambdaReport.contains("lambda$"), lambdaReport);
        assertFalse(lambdaReport.matches("(?s).*\\([^()\\s]+\\.java:\\d+\\).*"), lambdaReport);
    }

    @Test
    void a_plain_class_with_a_mutable_static_field_keeps_only_its_plain_class_line() {
        EvaluationResult result = evaluate(api("MutablePlain"), "mutableplain");
        String report = violations(result.getFailureReport().toString());
        assertEquals(fix("MutablePlain")
                + " is a plain class; an API module allows a final constant holder with static final fields "
                + "and a private constructor", report.trim());
        assertFalse(report.contains("HashMap"), report);
    }

    @Test
    void rule_1_reports_one_line_per_refused_class() {
        Map<String, ModuleOwnership.Owned> one = new LinkedHashMap<>();
        one.put(fix("UsesOneTwice"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        one.put("com.fasterxml.jackson.databind.ObjectMapper", ModuleOwnership.Owned.thirdParty("jackson-databind"));
        String oneReport = evaluate(ApiQualityRules.serviceDependencies(overlay(one)), "usesonetwice")
                .getFailureReport().toString();
        assertEquals(1, countDepends(oneReport), oneReport);

        Map<String, ModuleOwnership.Owned> two = new LinkedHashMap<>();
        two.put(fix("UsesTwo"), ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        two.put("com.fasterxml.jackson.databind.ObjectMapper", ModuleOwnership.Owned.thirdParty("jackson-databind"));
        two.put("com.fasterxml.jackson.databind.JsonNode", ModuleOwnership.Owned.thirdParty("jackson-databind"));
        String twoReport = evaluate(ApiQualityRules.serviceDependencies(overlay(two)), "usestwo")
                .getFailureReport().toString();
        assertEquals(2, countDepends(twoReport), twoReport);
    }

    /** The violation lines, without the rule description that precedes them. */
    private static String violations(String report) {
        int header = report.indexOf("was violated");
        if (header < 0) {
            return report;
        }
        int newline = report.indexOf('\n', header);
        return newline < 0 ? "" : report.substring(newline + 1);
    }

    private static int countDepends(String report) {
        int count = 0;
        for (String line : report.split("\n")) {
            if (line.contains(" depends on ")) {
                count++;
            }
        }
        return count;
    }

    private static ArchRule api(String simple) {
        return ApiQualityRules.apiContracts(overlay(ours(fix(simple), "sample-api", ModuleOwnership.Role.API)));
    }

    private static void assertValue(String simple, String... snippets) {
        EvaluationResult result = evaluate(api(simple), simple.toLowerCase());
        String report = result.getFailureReport().toString();
        assertTrue(result.hasViolation(), () -> simple + " passed:\n" + report);
        for (String snippet : snippets) {
            assertTrue(report.contains(fix(simple) + " " + snippet),
                    () -> "expected \"" + snippet + "\" in:\n" + report);
        }
        assertFalse(report.matches("(?s).*\\([^()\\s]+\\.java:\\d+\\).*"), () -> report);
    }

    private static String fix(String simple) {
        return FIXTURES + simple;
    }

    private static String implementsOrdinary(String className) {
        return className + " implements " + fix("Ordinary") + " from API module sample-api";
    }

    /** Ordinary is the API interface. Each subject is service code in the sample module. */
    private static Map<String, ModuleOwnership.Owned> ordinaryOwners(String... subjects) {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        owners.put(fix("Ordinary"), ModuleOwnership.Owned.ours("io.freedriver", "sample-api"));
        for (String subject : subjects) {
            owners.put(subject, ModuleOwnership.Owned.ours("io.freedriver", "sample"));
        }
        return owners;
    }

    private static List<String> rule3Lines(Map<String, ModuleOwnership.Owned> owners, String fixture) {
        return evaluate(ApiQualityRules.implementationsLiveInImpl(overlay(owners)), fixture)
                .getFailureReport().getDetails().stream().sorted().toList();
    }

    private static String rule3Report(Map<String, ModuleOwnership.Owned> owners, String fixture) {
        return evaluate(ApiQualityRules.implementationsLiveInImpl(overlay(owners)), fixture)
                .getFailureReport().toString();
    }

    /** The fixture's module. The artifactId has to give {@code role}, since the fixture package names none. */
    private static Map.Entry<String, ModuleOwnership.Owned> ours(String name, String artifactId, ModuleOwnership.Role role) {
        ModuleOwnership.Owned owned = ModuleOwnership.Owned.ours("io.freedriver", artifactId);
        assertEquals(role, owned.effectiveRole(), artifactId);
        return Map.entry(name, owned);
    }

    @SafeVarargs
    private static ModuleOwnership overlay(Map.Entry<String, ModuleOwnership.Owned>... entries) {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        for (Map.Entry<String, ModuleOwnership.Owned> entry : entries) {
            owners.put(entry.getKey(), entry.getValue());
        }
        return overlay(owners);
    }

    private static ModuleOwnership overlay(Map<String, ModuleOwnership.Owned> owners) {
        return ModuleOwnership.reactor().overlay(owners);
    }

    private static void assertPasses(ArchRule rule, String fixture) {
        EvaluationResult result = evaluate(rule, fixture);
        assertFalse(result.hasViolation(), () -> fixture + ": " + result.getFailureReport());
    }

    private static void assertFails(ArchRule rule, String fixture, String name) {
        EvaluationResult result = evaluate(rule, fixture);
        assertTrue(result.hasViolation(), () -> fixture + " passed:\n" + result.getFailureReport());
        assertTrue(result.getFailureReport().toString().contains(name),
                () -> "expected \"" + name + "\" in:\n" + result.getFailureReport());
    }

    private static EvaluationResult evaluate(ArchRule rule, String fixture) {
        String pkg = "io.freedriver.architecture.fixtures.apiquality";
        JavaClasses classes = new ClassFileImporter().importPackages(pkg);
        assertTrue(classes.stream().anyMatch(type -> pkg.equals(type.getPackageName())),
                () -> fixture + ": imported no class from " + pkg);
        return rule.evaluate(classes);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String text = Files.readString(pom);
                    if (text.contains("<artifactId>freedriver-web</artifactId>")
                            && text.contains("<module>mqtt-contract</module>")) {
                        return dir;
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(pom.toString(), e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
    }

    private static void writeJava(Path root, String relative, String source) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static void compile(Path sourceRoot, Path out, List<Path> classpath) throws IOException {
        Files.createDirectories(out);
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(files::add);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("the test JDK has no Java compiler");
        }
        List<String> args = new ArrayList<>();
        args.add("-encoding");
        args.add("UTF-8");
        args.add("--release");
        args.add("23");
        args.add("-d");
        args.add(out.toString());
        if (!classpath.isEmpty()) {
            args.add("-classpath");
            args.add(String.join(":", classpath.stream().map(Path::toString).toList()));
        }
        args.addAll(files);
        int status = compiler.run(null, null, null, args.toArray(String[]::new));
        if (status != 0) {
            throw new IllegalStateException("compile failed: " + args);
        }
    }

    private static void jar(Path classes, Path jar) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out);
                Stream<Path> stream = Files.walk(classes)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                if (!name.endsWith(".class")) {
                    continue;
                }
                jos.putNextEntry(new JarEntry(name));
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
