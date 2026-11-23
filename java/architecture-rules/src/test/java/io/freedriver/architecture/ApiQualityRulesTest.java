package io.freedriver.architecture;

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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passing and failing fixtures for the three API quality rules, evaluated like {@link LayeringEdgeTest}.
 * The fixture supplies a module role. Directory names that differ from artifactIds are read from the
 * reactor poms, and a jar named slf4j-api stays third-party.
 */
class ApiQualityRulesTest {

    private static final String FIXTURES = "io.freedriver.architecture.fixtures.apiquality.";

    @Test
    void directory_names_differ_from_artifact_ids() {
        ModuleOwnership ownership = ModuleOwnership.reactor();
        Path root = repoRoot();

        ModuleOwnership.Owned mqtt = ownership.classifyUri(
                root.resolve("mqtt/target/classes/io/freedriver/mqtt/Marker.class").toUri());
        assertEquals("freedriver-mqtt", mqtt.artifactId());
        assertEquals(ModuleOwnership.Role.UTILITY, mqtt.role());

        ModuleOwnership.Owned sms = ownership.classifyUri(
                root.resolve("sms/target/classes/io/freedriver/sms/Marker.class").toUri());
        assertEquals("sms-server", sms.artifactId());
        assertEquals(ModuleOwnership.Role.SERVICE, sms.role());

        ModuleOwnership.Owned contract = ownership.classifyUri(
                root.resolve("mqtt-contract/target/classes/io/freedriver/mqtt/contract/Marker.class").toUri());
        assertEquals("freedriver-mqtt-contract", contract.artifactId());
        assertEquals(ModuleOwnership.Role.API, contract.role());
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
            assertTrue(result.getFailureReport().toString().contains("slf4j-api"),
                    () -> result.getFailureReport().toString());
            assertTrue(result.getFailureReport().toString().contains("UsesLogger"),
                    () -> result.getFailureReport().toString());
        } finally {
            delete(root);
        }
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
    void a_record_that_implements_a_same_module_interface_passes_rule_2_and_fails_rule_3() {
        Map<String, ModuleOwnership.Owned> owners = new LinkedHashMap<>();
        ModuleOwnership.Owned api = ModuleOwnership.Owned.ours("io.freedriver", "sample-api");
        owners.put(fix("Mark"), api);
        owners.put(fix("SealedCase"), api);
        ModuleOwnership ownership = overlay(owners);
        assertPasses(ApiQualityRules.apiContracts(ownership), "recordimpl");
        assertFails(ApiQualityRules.implementationsLiveInImpl(ownership), "recordimpl", fix("SealedCase"));
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
    }

    private static String fix(String simple) {
        return FIXTURES + simple;
    }

    private static Map.Entry<String, ModuleOwnership.Owned> ours(String name, String artifactId, ModuleOwnership.Role role) {
        return Map.entry(name, new ModuleOwnership.Owned(
                ModuleOwnership.Kind.OURS, "io.freedriver", artifactId, role, artifactId));
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
