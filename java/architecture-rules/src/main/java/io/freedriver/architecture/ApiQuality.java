package io.freedriver.architecture;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the API-quality rules against a module's {@code target/classes} and freezes the result.
 * The store is recorded on Java {@link LayeringTest#RECORDED_JAVA}. On any other JDK the check is
 * skipped, and under CI it fails. The failure names {@code ArchitectureTest}, the test the
 * re-record command runs.
 */
public final class ApiQuality {

    private static final Map<Path, JavaClasses> IMPORTED = new ConcurrentHashMap<>();
    private static final Set<String> STORE_FAILURES =
            Set.of("StoreUpdateFailedException", "StoreInitializationFailedException");

    private ApiQuality() {
    }

    /** Skips the gate off Java 23, and fails the build when CI is on another JDK. */
    public static void recordedJdk() {
        int feature = Runtime.version().feature();
        if (System.getenv("CI") != null && feature != LayeringTest.RECORDED_JAVA) {
            Assertions.fail("CI runs Java " + feature + ", and ArchitectureTest's frozen violations are recorded on Java "
                    + LayeringTest.RECORDED_JAVA + ". Re-record the store on Java " + feature
                    + " and raise RECORDED_JAVA, or run CI on Java " + LayeringTest.RECORDED_JAVA + ".");
        }
        Assumptions.assumeTrue(feature == LayeringTest.RECORDED_JAVA,
                () -> "ArchitectureTest's frozen violations are recorded on Java " + LayeringTest.RECORDED_JAVA
                        + "; this is Java " + feature + ", so they are skipped. Run ArchitectureTest on Java "
                        + LayeringTest.RECORDED_JAVA + " from the repository root: " + LayeringTest.command(false));
    }

    public static void check(ArchRule rule, JavaClasses classes) {
        check(rule, classes, null);
    }

    /**
     * Freezes {@code rule} in {@code store} when that directory is the judged module's store and this
     * test runs from a module downstream of it. A null store uses the running module's
     * {@code archunit.properties}.
     */
    public static void check(ArchRule rule, JavaClasses classes, Path store) {
        String creation = ArchConfiguration.get()
                .getPropertyOrDefault("freeze.store.default.allowStoreCreation", "false");
        String update = ArchConfiguration.get()
                .getPropertyOrDefault("freeze.store.default.allowStoreUpdate", "false");
        if (store == null) {
            freeze(rule, classes);
            return;
        }
        ArchConfiguration.withThreadLocalScope(config -> {
            config.setProperty("freeze.store.default.path", store.toAbsolutePath().toString());
            config.setProperty("freeze.store.default.allowStoreCreation", creation);
            config.setProperty("freeze.store.default.allowStoreUpdate", update);
            freeze(rule, classes);
        });
    }

    /**
     * Rules 1 to 4 for a module judged from a downstream module's test. The caller names both
     * directories, so each is a literal the build-cache input check can match to the running
     * module's {@code maven.build.cache.input} entries.
     *
     * @param classes the judged module's {@code target/classes}, relative to the running module
     * @param store the judged module's own {@code src/test/resources/archunit-store}
     */
    public static void checkModule(Path classes, Path store) {
        JavaClasses imported = importClasses(classes);
        for (ArchRule rule : ApiQualityRules.all()) {
            check(rule, imported, store);
        }
    }

    public static JavaClasses main() {
        return importClasses(Path.of("target", "classes"));
    }

    public static JavaClasses importClasses(Path classesDir) {
        Path absolute = classesDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IllegalStateException(absolute + " is missing; build that module first");
        }
        return IMPORTED.computeIfAbsent(absolute, path -> new ClassFileImporter().importPath(path));
    }

    private static void freeze(ArchRule rule, JavaClasses classes) {
        try {
            FreezingArchRule.freeze(rule).check(classes);
        } catch (RuntimeException e) {
            if (!STORE_FAILURES.contains(e.getClass().getSimpleName())) {
                throw e;
            }
            throw new AssertionError("The frozen violations of ArchitectureTest no longer match the code: "
                    + "a frozen violation no longer occurs, or the rule's text changed. Re-record the store "
                    + "on Java " + LayeringTest.RECORDED_JAVA + " from the repository root, then commit the "
                    + "files under src/test/resources/archunit-store. The command runs ArchitectureTest: "
                    + LayeringTest.command(true) + "\nRule: " + rule.getDescription(), e);
        }
    }
}
