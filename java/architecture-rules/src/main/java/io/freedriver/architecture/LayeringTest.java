package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
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
 */
public abstract class LayeringTest {

    private static final Map<Path, JavaClasses> IMPORTED = new ConcurrentHashMap<>();

    /** The one package that holds this module's domain exceptions. */
    protected abstract String exceptionPackage();

    @Test
    void jakarta_ws_rs_stays_at_the_edge() {
        frozen(LayeringRules.jaxRsStaysAtTheEdge()).check(main());
    }

    @Test
    void every_resource_implements_its_api() {
        frozen(LayeringRules.resourcesImplementTheirApi()).check(main());
    }

    @Test
    void contract_annotations_sit_on_the_api() {
        frozen(LayeringRules.resourcesCarryNoContractAnnotations()).check(main());
    }

    @Test
    void resource_methods_return_dtos() {
        frozen(LayeringRules.resourceMethodsReturnDtos()).check(main());
    }

    @Test
    void security_identity_stays_at_the_edge() {
        frozen(LayeringRules.securityIdentityStaysAtTheEdge()).check(main());
    }

    @Test
    void service_unit_tests_avoid_jakarta_ws_rs() {
        frozen(LayeringRules.serviceUnitTestsAvoidJaxRs()).check(tests());
    }

    @Test
    void smallrye_rate_limit_is_not_used() {
        frozen(LayeringRules.noSmallRyeRateLimit()).check(main());
    }

    @Test
    void domain_exceptions_live_in_one_package() {
        frozen(LayeringRules.exceptionsLiveIn(exceptionPackage())).check(main());
    }

    @Test
    void mongo_only_from_repositories() {
        frozen(LayeringRules.mongoOnlyFromRepositories()).check(main());
    }

    @Test
    void no_foreign_http_redis_or_keycloak_clients() {
        frozen(LayeringRules.noForeignClients()).check(main());
    }

    private static ArchRule frozen(ArchRule rule) {
        return FreezingArchRule.freeze(rule);
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
