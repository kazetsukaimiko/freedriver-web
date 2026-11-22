package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role rules on fixture classes under {@code io.freedriver.architecture.fixtures}: each rule
 * fails on the fixture that breaks it, names the class or field, and passes on the fixtures that
 * keep it.
 */
class RoleRulesTest {

    private static final String FIXTURES = "io.freedriver.architecture.fixtures.";

    @Test
    void a_class_with_no_role_fails_and_is_named() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "norole");
        assertViolations(result, 1);
        assertReports(result, "io.freedriver.architecture.fixtures.norole.Orphan has no role: neither it nor package "
                + "io.freedriver.architecture.fixtures.norole carries one");
    }

    @Test
    void a_class_with_two_roles_fails_and_is_named() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "tworoles");
        assertViolations(result, 1);
        assertReports(result, "io.freedriver.architecture.fixtures.tworoles.Torn carries two roles: @Application, "
                + "@PrimaryAdapter");
    }

    @Test
    void a_role_of_its_own_in_a_port_package_fails() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "portpackage");
        assertViolations(result, 1);
        assertReports(result, "io.freedriver.architecture.fixtures.portpackage.Misplaced carries @Application in package "
                + "io.freedriver.architecture.fixtures.portpackage, which carries @SecondaryPort");
    }

    @Test
    void a_nested_class_with_a_role_of_its_own_fails() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "nested");
        assertViolations(result, 1);
        assertReports(result, "io.freedriver.architecture.fixtures.nested.Outer$Inner carries @SecondaryPort; a nested "
                + "class takes the role of io.freedriver.architecture.fixtures.nested.Outer");
    }

    @Test
    void the_unqualified_port_is_for_a_config_mapping_interface_and_adapter_for_no_class() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "unqualified");
        assertViolations(result, 3);
        String fixture = "io.freedriver.architecture.fixtures.unqualified.";
        assertReports(result, fixture + "PortClass has the unqualified @Port; it is for a @ConfigMapping interface only");
        assertReports(result, fixture + "PortInterface has the unqualified @Port; it is for a @ConfigMapping interface only");
        assertReports(result, fixture + "AnyAdapter has the unqualified @Adapter; it is for a @ConfigMapping interface only "
                + "(@Port) or for no class (@Adapter)");
        assertFalse(result.getFailureReport().getDetails().stream().anyMatch(l -> l.contains("Settings ")),
                "the @ConfigMapping interface with @Port passes");
    }

    @Test
    void roles_from_the_package_and_from_the_class_pass() {
        assertPasses(RoleRules.everyClassHasOneRole(), "hexagon");
        assertPasses(RoleRules.everyClassHasOneRole(), "state");
        assertPasses(RoleRules.everyClassHasOneRole(), "configport");
    }

    @Test
    void a_package_of_checked_classes_with_no_package_role_fails_and_names_the_class() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "missingpackagerole");
        assertViolations(result, 1);
        assertReports(result, "io.freedriver.architecture.fixtures.missingpackagerole.Placed is in package "
                + "io.freedriver.architecture.fixtures.missingpackagerole, which carries no role");
    }

    @Test
    void a_package_info_with_two_roles_fails_even_when_the_class_overrides_them() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "packagetworoles");
        assertViolations(result, 1);
        assertReports(result, "package io.freedriver.architecture.fixtures.packagetworoles carries two roles: "
                + "@Application, @PrimaryAdapter");
        assertReports(result, "io.freedriver.architecture.fixtures.packagetworoles.Covered is in it");
    }

    @Test
    void a_class_that_repeats_its_package_role_fails() {
        EvaluationResult result = evaluate(RoleRules.everyClassHasOneRole(), "redundant");
        assertViolations(result, 1);
        assertReports(result, "io.freedriver.architecture.fixtures.redundant.Repeat repeats package "
                + "io.freedriver.architecture.fixtures.redundant's role @SecondaryAdapter");
    }

    /**
     * Probe for the dispatcher layout (#612). A {@code @SecondaryAdapter} in a {@code @Port} package
     * calls a {@code @SecondaryAdapter} in another package. A port must not depend on an adapter, so
     * a package-wide {@code @Port} names {@code PortCaller} in the violation. jMolecules 0.33.0 judges
     * the class by its own role, and that call has no violation. An unrelated violation fails this
     * test instead of choosing a layout.
     */
    @Test
    void a_class_in_a_port_package_is_judged_by_its_own_role() {
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURES + "portown", FIXTURES + "portpeer");
        assertTrue(classes.contain(FIXTURES + "portown.PortCaller"));
        assertTrue(classes.contain(FIXTURES + "portpeer.PortPeer"));
        EvaluationResult result = RoleRules.hexagonal().evaluate(RoleRules.prodClasses(classes));
        String details = String.join("\n", result.getFailureReport().getDetails());
        assertFalse(details.contains("PortCaller"),
                () -> "jMolecules put the package in the port:\n" + result.getFailureReport());
        assertFalse(result.hasViolation(), () -> "unrelated violation:\n" + result.getFailureReport());
    }

    @Test
    void the_hexagon_passes_with_nested_classes_in_their_top_level_class_role() {
        assertPasses(RoleRules.hexagonal(), "hexagon");
    }

    @Test
    void an_application_class_that_calls_a_primary_adapter_fails() {
        EvaluationResult result = evaluate(RoleRules.hexagonal(), "hexagonbroken");
        assertTrue(result.hasViolation(), "an application class calling a primary adapter breaks the hexagon");
        assertReports(result, "io.freedriver.architecture.fixtures.hexagonbroken.Core.run()");
        assertReports(result, "io.freedriver.architecture.fixtures.hexagonbroken.Edge");
    }

    @Test
    void a_concurrent_hash_map_field_fails_and_is_named() {
        EvaluationResult result = evaluate(RoleRules.noStateInProcessMemory(), "state");
        assertReports(result, "Field <io.freedriver.architecture.fixtures.state.Remembers.waiters> holds a "
                + "java.util.concurrent.ConcurrentHashMap");
    }

    @Test
    void a_scoped_bean_with_a_port_role_is_judged_and_an_entity_is_not() {
        EvaluationResult result = evaluate(RoleRules.noStateInProcessMemory(), "beanstate");
        assertViolations(result, 1);
        assertReports(result, "Field <io.freedriver.architecture.fixtures.beanstate.CachingRepository.indexed> holds a "
                + "java.util.concurrent.ConcurrentHashMap");
    }

    @Test
    void the_same_field_with_process_local_passes() {
        EvaluationResult result = evaluate(RoleRules.noStateInProcessMemory(), "state");
        assertFalse(result.getFailureReport().toString().contains("Declares"),
                () -> "@ProcessLocal declares the field: " + result.getFailureReport());
    }

    @Test
    void a_field_that_is_not_final_fails() {
        EvaluationResult result = evaluate(RoleRules.noStateInProcessMemory(), "state");
        assertReports(result, "Field <io.freedriver.architecture.fixtures.state.Counts.count> is not final");
    }

    @Test
    void a_static_map_filled_at_run_time_fails() {
        EvaluationResult result = evaluate(RoleRules.noStateInProcessMemory(), "state");
        assertReports(result, "Field <io.freedriver.architecture.fixtures.state.StaticCache.CACHE> holds a java.util.Map");
    }

    @Test
    void static_constants_and_nested_values_pass() {
        EvaluationResult result = evaluate(RoleRules.noStateInProcessMemory(), "state");
        assertFalse(result.getFailureReport().toString().contains("Constants"),
                () -> "constants are no state: " + result.getFailureReport());
        assertViolations(result, 3);
    }

    @Test
    void a_dev_and_test_fake_is_exempt_and_a_prod_class_is_judged() {
        JavaClasses prod = RoleRules.prodClasses(imported("fake"));
        EvaluationResult result = RoleRules.noStateInProcessMemory().evaluate(prod);
        assertViolations(result, 1);
        assertReports(result, "Field <io.freedriver.architecture.fixtures.fake.ProdSender.sent> holds a java.util.List");
        assertFalse(prod.contain("io.freedriver.architecture.fixtures.fake.FakeSender"),
                "the dev and test fake is outside the prod classes");
    }

    private static EvaluationResult evaluate(ArchRule rule, String fixture) {
        return rule.evaluate(RoleRules.prodClasses(imported(fixture)));
    }

    private static JavaClasses imported(String fixture) {
        return new ClassFileImporter().importPackages(FIXTURES + fixture);
    }

    private static void assertPasses(ArchRule rule, String fixture) {
        EvaluationResult result = evaluate(rule, fixture);
        assertFalse(result.hasViolation(), () -> fixture + ": " + result.getFailureReport());
    }

    private static void assertViolations(EvaluationResult result, int count) {
        assertEquals(count, result.getFailureReport().getDetails().size(), () -> result.getFailureReport().toString());
    }

    private static void assertReports(EvaluationResult result, String text) {
        assertTrue(result.getFailureReport().toString().contains(text),
                () -> "expected \"" + text + "\" in:\n" + result.getFailureReport());
    }
}
