package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The layering rules for one module. A module's {@code ArchitectureTest} extends this and names
 * its exception package. Each rule is frozen: the violations recorded under
 * {@code src/test/resources/archunit-store} pass, and only a new one fails. The classes come from
 * this module's own {@code target/classes} and {@code target/test-classes}, so a dependency's
 * classes are not judged here.
 *
 * <p>The stores are recorded on Java {@value #RECORDED_JAVA}, the CI JDK: ArchUnit reads a newer JDK's
 * classes differently, so on another JDK the rules are skipped rather than compared with the store.
 */
public abstract class LayeringTest {

    /** The Java feature version the stores under archunit-store are recorded on. */
    public static final int RECORDED_JAVA = 23;

    private static final Map<Path, JavaClasses> IMPORTED = new ConcurrentHashMap<>();

    /** ArchUnit's failures when the store and the code disagree and the store may not be rewritten. */
    private static final java.util.Set<String> STORE_FAILURES =
            java.util.Set.of("StoreUpdateFailedException", "StoreInitializationFailedException");

    /** The one package that holds this module's domain exceptions. */
    protected abstract String exceptionPackage();

    @BeforeEach
    void onTheRecordedJdk() {
        if (System.getenv("CI") != null && Runtime.version().feature() != RECORDED_JAVA) {
            Assertions.fail("CI runs Java " + Runtime.version().feature() + ", and the layering rules' frozen violations "
                    + "are recorded on Java " + RECORDED_JAVA + ". Re-record every module's store on Java "
                    + Runtime.version().feature() + " and raise RECORDED_JAVA, or run CI on Java " + RECORDED_JAVA + ".");
        }
        Assumptions.assumeTrue(Runtime.version().feature() == RECORDED_JAVA,
                () -> "The layering rules' frozen violations are recorded on Java " + RECORDED_JAVA + "; this is Java "
                        + Runtime.version().feature() + ", so they are skipped. Run them on Java " + RECORDED_JAVA
                        + " from the repository root: " + command(false));
    }

    @Test
    void jakarta_ws_rs_stays_at_the_edge() {
        check(LayeringRules.jaxRsStaysAtTheEdge(), main());
    }

    @Test
    void every_resource_implements_its_api() {
        check(LayeringRules.resourcesImplementTheirApi(), main());
    }

    @Test
    void contract_annotations_sit_on_the_api() {
        check(LayeringRules.resourcesCarryNoContractAnnotations(), main());
    }

    @Test
    void resource_methods_return_dtos() {
        check(LayeringRules.resourceMethodsReturnDtos(), main());
    }

    @Test
    void security_identity_stays_at_the_edge() {
        check(LayeringRules.securityIdentityStaysAtTheEdge(), main());
    }

    @Test
    void service_unit_tests_avoid_jakarta_ws_rs() {
        check(LayeringRules.serviceUnitTestsAvoidJaxRs(), tests());
    }

    @Test
    void smallrye_rate_limit_is_not_used() {
        check(LayeringRules.noSmallRyeRateLimit(), main());
    }

    @Test
    void domain_exceptions_live_in_one_package() {
        check(LayeringRules.exceptionsLiveIn(exceptionPackage()), main());
    }

    @Test
    void mongo_only_from_repositories() {
        check(LayeringRules.mongoOnlyFromRepositories(), main());
    }

    @Test
    void no_foreign_http_redis_or_keycloak_clients() {
        check(LayeringRules.noForeignClients(), main());
    }

    /**
     * Checks the frozen rule. A store that no longer matches (a frozen violation was fixed, or a
     * rule's text changed) fails with the command that re-records it.
     */
    private static void check(ArchRule rule, JavaClasses classes) {
        try {
            FreezingArchRule.freeze(rule).check(classes);
        } catch (RuntimeException e) {
            // ArchUnit's store exceptions are package-private; they are matched by name.
            if (!STORE_FAILURES.contains(e.getClass().getSimpleName())) {
                throw e;
            }
            throw new AssertionError("The frozen violations of this rule no longer match the code: a frozen "
                    + "violation no longer occurs, or the rule's text changed. Re-record the store on Java "
                    + RECORDED_JAVA + " from the repository root, then commit the files under "
                    + "src/test/resources/archunit-store: " + command(true) + "\nRule: " + rule.getDescription(), e);
        }
    }

    /** The Maven command for this module's ArchitectureTest on Java 23, optionally re-recording the store. */
    static String command(boolean record) {
        Path here = Path.of("").toAbsolutePath();
        Path root = here;
        while (root != null && !Files.exists(root.resolve("mvnw"))) {
            root = root.getParent();
        }
        String module = root == null ? here.getFileName().toString() : root.relativize(here).toString();
        return "docker run --rm --user \"$(id -u):$(id -g)\" -e HOME=/tmp -v \"$PWD\":/src -v \"$HOME/.m2\":/tmp/.m2 "
                + "-w /src maven:3.9-eclipse-temurin-" + RECORDED_JAVA + " mvn -q -Dmaven.repo.local=/tmp/.m2/repository -pl "
                + module + " -am test -Dtest=ArchitectureTest "
                + "-Dsurefire.failIfNoSpecifiedTests=false"
                + (record ? " -Darchunit.freeze.store.default.allowStoreUpdate=true "
                        + "-Darchunit.freeze.store.default.allowStoreCreation=true" : "");
    }

    private static JavaClasses main() {
        return imported(Path.of("target", "classes"));
    }

    private static JavaClasses tests() {
        return imported(Path.of("target", "test-classes"));
    }

    private static JavaClasses imported(Path path) {
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(path.toAbsolutePath() + " is missing; run the test from the module directory");
        }
        return IMPORTED.computeIfAbsent(path.toAbsolutePath(), p -> new ClassFileImporter().importPath(p));
    }
}
