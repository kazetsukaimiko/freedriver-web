package io.freedriver.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.jmolecules.archunit.JMoleculesArchitectureRules;
import org.jmolecules.archunit.JMoleculesArchitectureRules.StereotypeLookup;
import org.jmolecules.archunit.JMoleculesArchitectureRules.VerificationDepth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * The architecture-role rules (#608). Every class declares what it is with one of jMolecules'
 * hexagonal roles, on itself or on its package's {@code package-info.java}; the hexagonal rules
 * hold between the roles; and an application or adapter class keeps no state in process memory
 * unless that state carries {@code @ProcessLocal} with its reason. {@link RolesTest} runs them,
 * with no frozen violations, against one module's main classes.
 *
 * <p>A nested class takes the role of the top-level class around it. Classes a dev or test build
 * profile adds ({@code @IfBuildProfile} without {@code "prod"}) are left out, so the rules judge
 * the classes of the prod jar.
 */
public final class RoleRules {

    static final String HEXAGONAL = "org.jmolecules.architecture.hexagonal.";
    static final String APPLICATION = HEXAGONAL + "Application";
    static final String PRIMARY_PORT = HEXAGONAL + "PrimaryPort";
    static final String SECONDARY_PORT = HEXAGONAL + "SecondaryPort";
    static final String PRIMARY_ADAPTER = HEXAGONAL + "PrimaryAdapter";
    static final String SECONDARY_ADAPTER = HEXAGONAL + "SecondaryAdapter";
    /** A port both sides use, such as configuration that adapters and the core read. */
    static final String PORT = HEXAGONAL + "Port";
    static final String ADAPTER = HEXAGONAL + "Adapter";
    /** The roles a class may carry: jMolecules' hexagonal architecture annotations. */
    static final List<String> ROLES = List.of(
            APPLICATION, PRIMARY_PORT, SECONDARY_PORT, PORT, PRIMARY_ADAPTER, SECONDARY_ADAPTER, ADAPTER);
    /** The roles whose classes keep no state in process memory. */
    static final Set<String> STATELESS = Set.of(APPLICATION, PRIMARY_ADAPTER, SECONDARY_ADAPTER, ADAPTER);
    /**
     * Port roles on a package: jMolecules puts every class of such a package in that port's layer,
     * whatever the class itself carries, so a class there carries no other role.
     */
    static final Set<String> PACKAGE_WIDE = Set.of(PRIMARY_PORT, SECONDARY_PORT);

    public static final String PROCESS_LOCAL = "io.freedriver.stereotypes.ProcessLocal";
    static final String IF_BUILD_PROFILE = "io.quarkus.arc.profile.IfBuildProfile";

    /** A configuration interface: the only class the unqualified {@code @Port} is for. Matched by name. */
    static final String CONFIG_MAPPING = "io.smallrye.config.ConfigMapping";

    /** CDI scopes: a class with one is a bean the container keeps. Matched by name. */
    static final Set<String> CDI_SCOPES = Set.of(
            "jakarta.enterprise.context.ApplicationScoped", "jakarta.enterprise.context.RequestScoped",
            "jakarta.enterprise.context.SessionScoped", "jakarta.enterprise.context.Dependent",
            "jakarta.inject.Singleton");

    /** java.util types that hold mutable state and whose names end in none of the collection words. */
    private static final Set<String> MUTABLE_BY_NAME = Set.of(
            "java.util.Vector", "java.util.Stack", "java.util.Hashtable", "java.util.Properties",
            "java.util.Dictionary", "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.Executor", "java.util.concurrent.ExecutorService",
            "java.util.concurrent.ScheduledExecutorService");
    private static final List<String> COLLECTION_WORDS = List.of(
            "Collection", "List", "Set", "Map", "Queue", "Deque");
    /** Collection and map interfaces: the declared type of a constant built with List.of, Set.of or Map.of. */
    private static final Set<String> COLLECTION_INTERFACES = Set.of(
            "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map", "java.util.SequencedCollection",
            "java.util.SequencedSet", "java.util.SequencedMap", "java.util.SortedSet", "java.util.SortedMap",
            "java.util.NavigableSet", "java.util.NavigableMap");

    private RoleRules() {
    }

    /**
     * Rule 1: every top-level class has exactly one role, its own or its package's; a class's own
     * role takes the place of its package's. A nested class carries none of its own.
     */
    public static ArchRule everyClassHasOneRole() {
        return classes().that(DescribedPredicate.describe("are declared in a source file", RoleRules::declared))
                .should(haveOneRole())
                .because("a class says what it is: put one of @Application, @PrimaryPort, @SecondaryPort, "
                        + "@PrimaryAdapter or @SecondaryAdapter (org.jmolecules.architecture.hexagonal) on the "
                        + "package's package-info.java, and on a class only when its role differs from the package's. "
                        + "The unqualified @Port is for a @ConfigMapping interface, which both sides read; "
                        + "the unqualified @Adapter is for no class")
                .allowEmptyShould(true);
    }

    /**
     * Rule 2: jMolecules' hexagonal rules, strict. Adapters depend on ports, ports on ports, and the
     * application core on ports alone. A nested class counts as the role of its top-level class.
     */
    public static ArchRule hexagonal() {
        return JMoleculesArchitectureRules.ensureHexagonal(VerificationDepth.STRICT,
                StereotypeLookup.of(RoleRules::selfAndEnclosing, false));
    }

    /**
     * Rule 3: a class whose instances the container keeps (a CDI scope: {@code @ApplicationScoped},
     * {@code @Singleton}, {@code @RequestScoped}, {@code @SessionScoped}, {@code @Dependent}), and every
     * {@code @Application} or adapter class, keeps no state in process memory, whatever its role: a
     * repository bean is judged like the core. A Panache entity is no bean and is not judged. Every field is
     * final, and no field's type is a mutable collection or map, an atomic, a lock, an executor or
     * a {@code CompletableFuture}, unless the field or its class carries {@code @ProcessLocal}.
     *
     * <p>Static fields follow the same rule. A static final field typed as a collection or map
     * interface ({@code List}, {@code Set}, {@code Map}, ...) is a constant when the class's static
     * initializer creates none of the mutable types above, as with {@code Set.of(...)}. The fields of
     * a nested class's instances are values the class passes around; its static fields are judged.
     */
    public static ArchRule noStateInProcessMemory() {
        return classes().that(DescribedPredicate.describe(
                        "are declared in a source file and are a CDI bean, @Application or an adapter",
                        c -> declared(c) && (role(c).filter(STATELESS::contains).isPresent() || scoped(c))))
                .should(keepNoState())
                .because("state in one replica's memory breaks under a second replica or a restart: keep it in "
                        + "Mongo or Redis, or declare it with @ProcessLocal(\"<why one process is enough>\") "
                        + "(io.freedriver.stereotypes) on the field")
                .allowEmptyShould(true);
    }

    /** Rule 4: the classes of the prod jar, without the fakes a dev or test build profile adds. */
    public static JavaClasses prodClasses(JavaClasses classes) {
        return classes.that(DescribedPredicate.describe("are in the prod build", c -> !devOrTestOnly(c)));
    }

    /** A class's role: its own, or else its package's. Empty when it has none or more than one. */
    static Optional<String> role(JavaClass c) {
        JavaClass top = topLevel(c);
        List<String> own = roles(top);
        if (own.size() == 1) {
            return Optional.of(own.get(0));
        }
        List<String> inPackage = roles(top.getPackage());
        return own.isEmpty() && inPackage.size() == 1 ? Optional.of(inPackage.get(0)) : Optional.empty();
    }

    private static ArchCondition<JavaClass> haveOneRole() {
        return new ArchCondition<>("have exactly one architecture role, their own or their package's") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                List<String> own = roles(c);
                String pkg = c.getPackageName();
                List<String> inPackage = roles(c.getPackage());
                if (c.getEnclosingClass().isPresent()) {
                    if (!own.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(c, c.getName() + " carries " + names(own)
                                + "; a nested class takes the role of " + topLevel(c).getName()));
                    }
                } else if (own.size() > 1) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " carries two roles: " + names(own)));
                } else if (inPackage.size() > 1) {
                    events.add(SimpleConditionEvent.violated(c, "package " + pkg + " carries two roles: "
                            + names(inPackage) + "; " + c.getName() + " is in it"));
                } else if (unqualified(c).isPresent()) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " has the unqualified "
                            + names(List.of(unqualified(c).get())) + "; it is for a @ConfigMapping interface only "
                            + "(@Port) or for no class (@Adapter): give it a primary or secondary role"));
                } else if (own.isEmpty() && inPackage.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " has no role: neither it nor package "
                            + pkg + " carries one"));
                } else if (own.size() == 1 && inPackage.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " is in package " + pkg
                            + ", which carries no role"));
                } else if (own.size() == 1 && own.equals(inPackage) && !configMappingPort(c)) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " repeats package " + pkg + "'s role "
                            + names(own) + "; a class carries a role only when it differs from its package"));
                } else if (own.size() == 1 && inPackage.size() == 1 && PACKAGE_WIDE.contains(inPackage.get(0))
                        && !own.equals(inPackage)) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " carries " + names(own) + " in package "
                            + pkg + ", which carries " + names(inPackage) + "; jMolecules puts every class of that "
                            + "package in the port, so move the port role from the package to its classes"));
                }
            }
        };
    }

    /**
     * A {@code @ConfigMapping} interface whose own role is {@code @Port}. Repeating the package's
     * {@code @Port} is how configuration is declared, so that pair is not a redundant role.
     */
    private static boolean configMappingPort(JavaClass c) {
        return c.isInterface() && c.isAnnotatedWith(CONFIG_MAPPING) && roles(c).equals(List.of(PORT));
    }

    /**
     * The unqualified role a top-level class has when it may not: {@code @Adapter} on any class, and
     * {@code @Port} on anything but a {@code @ConfigMapping} interface. Otherwise any class could take
     * the unqualified port to leave rule 2's direction checks.
     */
    private static Optional<String> unqualified(JavaClass c) {
        Optional<String> role = role(c);
        if (role.filter(ADAPTER::equals).isPresent()) {
            return role;
        }
        boolean configMapping = c.isInterface() && c.isAnnotatedWith(CONFIG_MAPPING);
        return role.filter(PORT::equals).filter(r -> !configMapping);
    }

    /** The class, or the top-level class it is nested in, carries a CDI scope. */
    static boolean scoped(JavaClass c) {
        JavaClass top = topLevel(c);
        return CDI_SCOPES.stream().anyMatch(top::isAnnotatedWith);
    }

    private static ArchCondition<JavaClass> keepNoState() {
        return new ArchCondition<>("keep no state in process memory unless it carries @ProcessLocal") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                if (selfAndEnclosing(c).anyMatch(k -> k.isAnnotatedWith(PROCESS_LOCAL))) {
                    return;
                }
                boolean nested = c.getEnclosingClass().isPresent();
                boolean createsMutableStatics = createsMutableStatics(c);
                for (JavaField f : c.getFields()) {
                    boolean isStatic = f.getModifiers().contains(JavaModifier.STATIC);
                    if (f.getModifiers().contains(JavaModifier.SYNTHETIC) || f.isAnnotatedWith(PROCESS_LOCAL)
                            || (nested && !isStatic)) {
                        continue;
                    }
                    String why = stateOf(f, isStatic, createsMutableStatics);
                    if (why != null) {
                        events.add(SimpleConditionEvent.violated(f, "Field <" + f.getFullName() + "> " + why
                                + "; declare it with @ProcessLocal(\"<reason>\") or keep the state out of "
                                + "process memory"));
                    }
                }
            }
        };
    }

    /** Why the field is state in process memory, or null when it is none. */
    private static String stateOf(JavaField f, boolean isStatic, boolean createsMutableStatics) {
        if (!f.getModifiers().contains(JavaModifier.FINAL)) {
            return "is not final";
        }
        JavaClass type = f.getRawType();
        if (!mutable(type)) {
            return null;
        }
        if (isStatic && COLLECTION_INTERFACES.contains(type.getName()) && !createsMutableStatics) {
            return null;
        }
        return "holds a " + type.getName();
    }

    /** Whether the class's static initializer creates a mutable collection, atomic, lock, executor or future. */
    private static boolean createsMutableStatics(JavaClass c) {
        return c.getStaticInitializer()
                .map(init -> init.getConstructorCallsFromSelf().stream().anyMatch(call -> mutable(call.getTargetOwner()))
                        || init.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> {
                            JavaClass returned = call.getTarget().getRawReturnType();
                            return mutable(returned) && !COLLECTION_INTERFACES.contains(returned.getName());
                        }))
                .orElse(false);
    }

    /**
     * A mutable collection or map, an atomic, a lock, an executor or a {@code CompletableFuture}.
     * The JDK types are matched by name as well, so the rule holds when ArchUnit cannot read the
     * running JDK's class files.
     */
    static boolean mutable(JavaClass type) {
        if (type.isPrimitive() || type.isArray()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.util.concurrent.atomic.") || name.startsWith("java.util.concurrent.locks.")
                || MUTABLE_BY_NAME.contains(name)) {
            return true;
        }
        if ((type.getPackageName().equals("java.util") || type.getPackageName().equals("java.util.concurrent"))
                && COLLECTION_WORDS.stream().anyMatch(type.getSimpleName()::endsWith)) {
            return true;
        }
        return type.isAssignableTo("java.util.Collection") || type.isAssignableTo("java.util.Map")
                || type.isAssignableTo("java.util.concurrent.Executor")
                || type.isAssignableTo("java.util.concurrent.CompletableFuture");
    }

    static boolean declared(JavaClass c) {
        return !c.getSimpleName().equals("package-info") && !c.getName().endsWith(".package-info")
                && !c.getModifiers().contains(JavaModifier.SYNTHETIC) && !c.isAnonymousClass();
    }

    private static boolean devOrTestOnly(JavaClass c) {
        JavaClass top = topLevel(c);
        if (!top.isAnnotatedWith(IF_BUILD_PROFILE)) {
            return false;
        }
        JavaAnnotation<JavaClass> profile = top.getAnnotationOfType(IF_BUILD_PROFILE);
        List<String> names = new ArrayList<>();
        for (String property : List.of("value", "anyOf", "allOf")) {
            profile.get(property).ifPresent(v -> {
                if (v instanceof Object[] many) {
                    for (Object one : many) {
                        names.add(String.valueOf(one));
                    }
                } else {
                    names.add(String.valueOf(v));
                }
            });
        }
        return !names.contains("prod");
    }

    private static List<String> roles(HasAnnotations<?> annotated) {
        return ROLES.stream().filter(annotated::isAnnotatedWith).toList();
    }

    private static String names(Collection<String> roles) {
        return String.join(", ", roles.stream().map(r -> "@" + r.substring(HEXAGONAL.length())).toList());
    }

    private static JavaClass topLevel(JavaClass c) {
        JavaClass k = c;
        while (k.getEnclosingClass().isPresent()) {
            k = k.getEnclosingClass().get();
        }
        return k;
    }

    /** The class (an array's element class) and the classes around it, whose roles it takes. */
    private static Stream<JavaClass> selfAndEnclosing(JavaClass c) {
        List<JavaClass> chain = new ArrayList<>();
        for (JavaClass k = c.isArray() ? c.getBaseComponentType() : c; k != null; k = k.getEnclosingClass().orElse(null)) {
            chain.add(k);
        }
        return chain.stream();
    }
}
