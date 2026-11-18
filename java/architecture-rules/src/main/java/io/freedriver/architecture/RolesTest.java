package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The architecture-role rules (#608) for one module, with no frozen violations: every violation
 * fails. A module whose main classes carry their roles has a {@code ClassRolesTest} that extends
 * this. The classes come from the module's own {@code target/classes}, without the fakes a dev or
 * test build profile adds.
 */
public abstract class RolesTest {

    @Test
    void every_class_has_one_role() {
        RoleRules.everyClassHasOneRole().check(main());
    }

    @Test
    void the_roles_keep_the_hexagonal_rules() {
        RoleRules.hexagonal().check(main());
    }

    @Test
    void application_and_adapters_keep_no_state_in_process_memory() {
        RoleRules.noStateInProcessMemory().check(main());
    }

    private static JavaClasses main() {
        Path path = Path.of("target", "classes");
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(path.toAbsolutePath() + " is missing; run the test from the module directory");
        }
        return RoleRules.prodClasses(new ClassFileImporter().importPath(path));
    }
}
