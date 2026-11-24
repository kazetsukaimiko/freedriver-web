package io.freedriver.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;


/**
 * The main outputs of several reactor modules, read one output at a time. Each output is a
 * {@code target/classes} directory or a module's jar, and its module is resolved before its classes
 * join the others, so a package or a class name that two modules both hold stays visible. Two
 * outputs of one module, its classes directory and its own jar, count as that one module.
 */
public final class ReactorOutputs {

    private static final String BECAUSE = "a package's main classes come from one reactor module; two "
            + "modules that share a package cannot both be on a module path, and a class name that two "
            + "modules compile resolves to whichever comes first on the classpath";

    private final List<Location> locations;
    private final Map<String, Set<String>> modulesByPackage;
    private final Map<String, Set<String>> modulesByClass;

    private ReactorOutputs(List<Location> locations, Map<String, Set<String>> modulesByPackage,
                           Map<String, Set<String>> modulesByClass) {
        this.locations = locations;
        this.modulesByPackage = modulesByPackage;
        this.modulesByClass = modulesByClass;
    }

    /**
     * @param outputs each module's {@code target/classes}, or its jar under {@code target}
     * @throws IllegalArgumentException when an output is missing or belongs to no reactor module
     */
    public static ReactorOutputs of(List<Path> outputs) {
        return of(outputs, ModuleOwnership.reactor());
    }

    static ReactorOutputs of(List<Path> outputs, ModuleOwnership ownership) {
        List<Location> locations = new ArrayList<>();
        Map<String, Set<String>> byPackage = new TreeMap<>();
        Map<String, Set<String>> byClass = new TreeMap<>();
        for (Path output : outputs) {
            Path absolute = output.toAbsolutePath().normalize();
            if (!Files.exists(absolute)) {
                throw new IllegalArgumentException(absolute + " is missing; build that module first");
            }
            boolean jar = Files.isRegularFile(absolute);
            ModuleOwnership.Owned module = ownership.classifyUri(
                    jar ? URI.create("jar:" + absolute.toUri() + "!/") : absolute.toUri());
            if (!module.ours()) {
                throw new IllegalArgumentException(absolute + " is not the output of a reactor module");
            }
            Location location = jar ? Location.of(URI.create("jar:" + absolute.toUri() + "!/")) : Location.of(absolute);
            locations.add(location);
            for (JavaClass type : new ClassFileImporter().importLocations(List.of(location))) {
                if (!main(type)) {
                    continue;
                }
                byPackage.computeIfAbsent(type.getPackageName(), key -> new TreeSet<>()).add(module.artifactId());
                byClass.computeIfAbsent(type.getName(), key -> new TreeSet<>()).add(module.artifactId());
            }
        }
        return new ReactorOutputs(List.copyOf(locations), byPackage, byClass);
    }

    /** Every output's classes together, the subjects of {@link #packagesComeFromOneModule()}. */
    public JavaClasses classes() {
        return new ClassFileImporter().importLocations(locations);
    }

    /**
     * No package's main classes come from two reactor modules. Each class in a shared package is its
     * own violation, so a class added to a package that is already split is a new line.
     */
    public ArchRule packagesComeFromOneModule() {
        return ArchRuleDefinition.classes().that(DescribedPredicate.describe(
                        "are main classes of the reactor's modules", ReactorOutputs::main))
                .should(new ArchCondition<>("be in a package that one reactor module holds") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        Set<String> compiledIn = modulesByClass.getOrDefault(type.getName(), Set.of());
                        if (compiledIn.size() > 1) {
                            events.add(SimpleConditionEvent.violated(type, type.getName()
                                    + " is compiled in reactor modules " + String.join(" and ", compiledIn)));
                        }
                        Set<String> holders = modulesByPackage.getOrDefault(type.getPackageName(), Set.of());
                        if (holders.size() > 1) {
                            events.add(SimpleConditionEvent.violated(type, type.getName() + " is in package "
                                    + type.getPackageName() + ", whose main classes come from reactor modules "
                                    + String.join(" and ", holders)));
                        }
                    }
                })
                .because(BECAUSE)
                .allowEmptyShould(true);
    }

    private static boolean main(JavaClass type) {
        return !"package-info".equals(type.getSimpleName()) && !"module-info".equals(type.getSimpleName())
                && !type.isAnonymousClass();
    }
}
