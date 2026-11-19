package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JAX-RS and security edges on fixture classes under {@code io.freedriver.architecture.fixtures.layering}.
 * Each fixture depends on {@code jakarta.ws.rs.core.Response} and on {@code SecurityIdentity}.
 */
class LayeringEdgeTest {

    private static final String FIXTURES = "io.freedriver.architecture.fixtures.layering.";

    @Test
    void a_primary_adapter_from_the_package_passes_both() {
        assertPasses("primary");
    }

    @Test
    void a_secondary_adapter_from_the_package_passes_jax_rs_and_fails_security() {
        assertPasses(LayeringRules.jaxRsStaysAtTheEdge(), "secondary");
        assertFails(LayeringRules.securityIdentityStaysAtTheEdge(), "secondary",
                "io.freedriver.architecture.fixtures.layering.secondary.FromPackage");
    }

    @Test
    void a_nested_class_in_a_primary_adapter_passes_both() {
        assertPasses("nested");
    }

    @Test
    void a_non_adapter_role_named_resource_fails_both() {
        String pkg = "io.freedriver.architecture.fixtures.layering.other.";
        assertFailsBoth("other", pkg + "AppResource", pkg + "AdapterResource");
    }

    @Test
    void a_class_with_no_role_passes_both_by_the_name_fallback() {
        assertPasses("fallback");
    }

    @Test
    void two_roles_named_resource_fail_both_with_no_fallback() {
        assertFailsBoth("conflict", "io.freedriver.architecture.fixtures.layering.conflict.SplitResource");
    }

    @Test
    void the_because_texts_name_the_adapter_roles() {
        String jaxRs = LayeringRules.jaxRsStaysAtTheEdge().getDescription();
        assertTrue(jaxRs.contains("@PrimaryAdapter"), jaxRs);
        assertTrue(jaxRs.contains("@SecondaryAdapter"), jaxRs);

        String security = LayeringRules.securityIdentityStaysAtTheEdge().getDescription();
        assertTrue(security.contains("@PrimaryAdapter"), security);
        int secondary = security.indexOf("@SecondaryAdapter");
        assertTrue(secondary > 0 && security.indexOf("@SecondaryAdapter", secondary + 1) < 0, security);
        assertTrue(security.regionMatches(secondary - 2, "a @SecondaryAdapter is outside", 0,
                "a @SecondaryAdapter is outside".length()), security);
    }

    private static void assertPasses(String fixture) {
        assertPasses(LayeringRules.jaxRsStaysAtTheEdge(), fixture);
        assertPasses(LayeringRules.securityIdentityStaysAtTheEdge(), fixture);
    }

    private static void assertFailsBoth(String fixture, String... names) {
        assertFails(LayeringRules.jaxRsStaysAtTheEdge(), fixture, names);
        assertFails(LayeringRules.securityIdentityStaysAtTheEdge(), fixture, names);
    }

    private static void assertPasses(ArchRule rule, String fixture) {
        EvaluationResult result = evaluate(rule, fixture);
        assertFalse(result.hasViolation(), () -> fixture + ": " + result.getFailureReport());
    }

    private static void assertFails(ArchRule rule, String fixture, String... names) {
        EvaluationResult result = evaluate(rule, fixture);
        assertTrue(result.hasViolation(), () -> fixture + " passed:\n" + result.getFailureReport());
        for (String name : names) {
            assertTrue(result.getFailureReport().toString().contains(name),
                    () -> "expected \"" + name + "\" in:\n" + result.getFailureReport());
        }
    }

    private static EvaluationResult evaluate(ArchRule rule, String fixture) {
        String pkg = FIXTURES + fixture;
        JavaClasses classes = new ClassFileImporter().importPackages(pkg, FIXTURES + "bystander");
        assertTrue(classes.stream().anyMatch(c -> pkg.equals(c.getPackageName())
                        && !"package-info".equals(c.getSimpleName())),
                () -> "imported no class from " + pkg);
        return rule.evaluate(classes);
    }
}
