package io.freedriver.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.LambdaMetafactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Rules 1 to 4 of the API quality gate and its two role rules: every class has a package role, and
 * a module holds one role. Each rule judges main classes only, and rules 1 to 3 pick
 * their subjects by {@link ModuleOwnership.Owned#effectiveRole()}: the role the class's package
 * names, else its module's artifact role. Every gated module runs all four, so a class is judged by
 * its role wherever it is compiled. {@link ApiQuality} freezes them per module.
 */
public final class ApiQualityRules {

    static final String REGISTER_REST_CLIENT = "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";

    /** How a class gets its role, stated once in each rule's text. */
    private static final String ROLES = "A class is API, implementation or service code by its package: "
            + "io.freedriver.<project> or io.freedriver.<project>.<area>, then api, impl.<name> or service, "
            + "and the packages below it. A class in any other package takes its module's role: API for an "
            + "artifactId ending -api, -model or -contract, implementation for one ending -impl, service "
            + "for the rest";

    private static final String SERVICE_BECAUSE = "service code depends only on java, javax, jakarta, "
            + "io.quarkus except io.quarkus.mongodb.panache and io.quarkus.hibernate.orm.panache, "
            + "io.smallrye, org.eclipse.microprofile, io.micrometer, annotation types, service code of its own "
            + "module, API code and the utility artifactIds stereotypes, mcp-support, hit-window and "
            + "freedriver-mqtt. " + ROLES + ". An HTTP edge class, one that observes "
            + "io.quarkus.vertx.http.runtime.filters.Filters or io.vertx.ext.web.Router, has a method "
            + "annotated @io.quarkus.vertx.web.Route or @io.quarkus.vertx.web.RouteFilter, or implements an "
            + "io.quarkus.oidc interface, may also depend on io.vertx.core and io.vertx.ext.web. An MCP edge "
            + "class, one with a method annotated from io.quarkiverse.mcp.server or that implements an "
            + "interface from that package, may also depend on io.quarkiverse.mcp.server";

    private static final String API_BECAUSE = "API code holds interfaces, records, enums, exceptions, "
            + "annotations and final constant holders (only static final fields and a private constructor), "
            + "carries no CDI scope, and depends only on java, javax, jakarta, org.eclipse.microprofile, "
            + "annotation types and API code. " + ROLES + " "
            + ApiValues.BECAUSE;

    private static final String IMPL_BECAUSE = "a main class that implements an API interface is "
            + "implementation code; a JAX-RS resource may implement its JAX-RS API interface, a generated "
            + "MicroProfile REST client is exempt, and a record, enum, interface, exception or permitted "
            + "subtype of a sealed API interface in API code may implement an API interface. " + ROLES;

    private static final String IMPL_PACKAGE_BECAUSE = "an implementation is a package "
            + "io.freedriver..impl.<name> and its subpackages; only its own classes depend on it, and it "
            + "depends on no class in a package io.freedriver..service.. and on no other implementation";

    private static final String PACKAGE_ROLE_BECAUSE = "a main class under io.freedriver is in a package "
            + "io.freedriver.<project> or io.freedriver.<project>.<area>, then api, impl.<name> or service, "
            + "or below one; the utility artifactIds stereotypes, mcp-support, hit-window and "
            + "freedriver-mqtt are exempt";

    private static final String ONE_ROLE_BECAUSE = "a module holds one role: API, implementation, service "
            + "or utility. " + ROLES;

    static final String OURS = "io.freedriver";

    private ApiQualityRules() {
    }

    /**
     * What every gated module runs on its main classes, in order: rules 1 to 4, then the two role
     * rules. The split-package rule needs every module's output at once and runs from
     * {@link ReactorOutputs}.
     */
    public static List<ArchRule> all() {
        return List.of(serviceDependencies(), apiContracts(), implementationsLiveInImpl(),
                implementationPackagesStayPrivate(), classesHaveAPackageRole(), modulesHoldOneRole());
    }

    /** Every main class sits in a package that names a role, or in a utility module. */
    public static ArchRule classesHaveAPackageRole() {
        return classesHaveAPackageRole(ModuleOwnership.reactor());
    }

    /** A module's main classes share one role. */
    public static ArchRule modulesHoldOneRole() {
        return modulesHoldOneRole(ModuleOwnership.reactor());
    }

    /** Rule 1. Service code depends on the allowlist. */
    public static ArchRule serviceDependencies() {
        return serviceDependencies(ModuleOwnership.reactor());
    }

    /** Rule 2. API code stays a contract. */
    public static ArchRule apiContracts() {
        return apiContracts(ModuleOwnership.reactor());
    }

    /** Rule 3. A class that implements an API interface is implementation code. */
    public static ArchRule implementationsLiveInImpl() {
        return implementationsLiveInImpl(ModuleOwnership.reactor());
    }

    /**
     * Rule 4. No class outside an implementation package depends on one inside it, and an
     * implementation depends on no service code and no other implementation.
     */
    public static ArchRule implementationPackagesStayPrivate() {
        return implementationPackagesStayPrivate(ModuleOwnership.reactor());
    }

    static ArchRule serviceDependencies(ModuleOwnership ownership) {
        return classes().that(subjects(ownership, ModuleOwnership.Role.SERVICE))
                .should(new ArchCondition<>("depend only on the service allowlist") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        ModuleOwnership.Owned self = ownership.classify(type);
                        for (JavaClass dependency : dependencies(type)) {
                            String why = serviceDependency(type, dependency, self, ownership);
                            if (why != null) {
                                events.add(SimpleConditionEvent.violated(type, type.getName()
                                        + " depends on " + dependency.getName() + " " + why));
                            }
                        }
                    }
                })
                .because(SERVICE_BECAUSE)
                .allowEmptyShould(true);
    }

    static ArchRule apiContracts(ModuleOwnership ownership) {
        return classes().that(subjects(ownership, ModuleOwnership.Role.API))
                .should(new ArchCondition<>("be contract types") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        if (!contractShape(type)) {
                            events.add(SimpleConditionEvent.violated(type, type.getName()
                                    + " is a plain class; an API module allows a final constant holder "
                                    + "with static final fields and a private constructor"));
                        }
                        if (cdiScope(type)) {
                            events.add(SimpleConditionEvent.violated(type, type.getName() + " carries a CDI scope"));
                        }
                        ModuleOwnership.Owned self = ownership.classify(type);
                        for (JavaClass dependency : dependencies(type)) {
                            String why = apiDependency(dependency, self, ownership);
                            if (why != null) {
                                events.add(SimpleConditionEvent.violated(type, type.getName()
                                        + " depends on " + dependency.getName() + " " + why));
                            }
                        }
                        if (contractShape(type)) {
                            ApiValues.check(type, events, ownership);
                        }
                    }
                })
                .because(API_BECAUSE)
                .allowEmptyShould(true);
    }

    static ArchRule implementationsLiveInImpl(ModuleOwnership ownership) {
        return classes().that(mainClasses(ownership))
                .should(new ArchCondition<>("be implementation code when they implement an API interface") {
                    private final Map<String, JavaClass> resolved = new HashMap<>();

                    @Override
                    public void init(Collection<JavaClass> all) {
                        resolved.clear();
                        for (JavaClass type : all) {
                            resolved.put(type.getName(), type);
                        }
                    }

                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        if (type.isAnnotatedWith(REGISTER_REST_CLIENT)) {
                            return;
                        }
                        ModuleOwnership.Owned self = ownership.classify(type);
                        if (self.is(ModuleOwnership.Role.IMPL)) {
                            return;
                        }
                        if (self.is(ModuleOwnership.Role.API) && apiContractType(type)) {
                            return;
                        }
                        for (JavaClass iface : implementedInterfaces(type, resolved)) {
                            ModuleOwnership.Owned owner = ownership.classify(iface);
                            if (!owner.is(ModuleOwnership.Role.API)) {
                                continue;
                            }
                            if (LayeringRules.resourceClass(type) && jaxRsContract(iface)) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(type, type.getName()
                                    + " implements " + iface.getName() + " from API module " + owner.artifactId()));
                        }
                    }
                })
                .because(IMPL_BECAUSE)
                .allowEmptyShould(true);
    }

    static ArchRule classesHaveAPackageRole(ModuleOwnership ownership) {
        return classes().that(mainClasses(ownership))
                .should(new ArchCondition<>("be in a package that names a role") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        ModuleOwnership.Owned self = ownership.classify(type);
                        if (self.packageRole().isPresent()
                                || ModuleOwnership.UTILITY_ARTIFACTS.contains(self.artifactId())
                                || !under(type.getPackageName(), OURS)) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(type, type.getName() + " is in package "
                                + type.getPackageName() + ", which names no role"));
                    }
                })
                .because(PACKAGE_ROLE_BECAUSE)
                .allowEmptyShould(true);
    }

    /**
     * A module's role is the one most of its main classes have. Each class with another role is
     * its own violation, so a class added to a module that already mixes roles is a new line.
     */
    static ArchRule modulesHoldOneRole(ModuleOwnership ownership) {
        Map<String, ModuleOwnership.Role> held = new HashMap<>();
        return classes().that(mainClasses(ownership))
                .should(new ArchCondition<>("share their module's one role") {
                    @Override
                    public void init(Collection<JavaClass> all) {
                        Map<String, Map<ModuleOwnership.Role, Integer>> counts = new HashMap<>();
                        for (JavaClass type : all) {
                            ModuleOwnership.Owned owned = ownership.classify(type);
                            counts.computeIfAbsent(module(owned), key -> new EnumMap<>(ModuleOwnership.Role.class))
                                    .merge(owned.effectiveRole(), 1, Integer::sum);
                        }
                        held.clear();
                        counts.forEach((module, roles) -> held.put(module, roles.entrySet().stream()
                                .max(Map.Entry.<ModuleOwnership.Role, Integer>comparingByValue()
                                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                                .orElseThrow().getKey()));
                    }

                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        ModuleOwnership.Owned self = ownership.classify(type);
                        ModuleOwnership.Role module = held.get(module(self));
                        if (self.effectiveRole() != module) {
                            events.add(SimpleConditionEvent.violated(type, type.getName() + " is "
                                    + named(self.effectiveRole()) + " code in module " + self.artifactId()
                                    + ", where most classes are " + named(module) + " code"));
                        }
                    }
                })
                .because(ONE_ROLE_BECAUSE)
                .allowEmptyShould(true);
    }

    private static String module(ModuleOwnership.Owned owned) {
        return owned.groupId() + ":" + owned.artifactId();
    }

    private static String named(ModuleOwnership.Role role) {
        return switch (role) {
            case API -> "API";
            case IMPL -> "implementation";
            case UTILITY -> "utility";
            case SERVICE -> "service";
        };
    }

    static ArchRule implementationPackagesStayPrivate(ModuleOwnership ownership) {
        return classes().that(mainClasses(ownership))
                .should(new ArchCondition<>("keep implementation packages private") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        String self = implementation(type.getPackageName());
                        for (JavaClass dependency : dependencies(type)) {
                            String why = implementationDependency(self, dependency.getPackageName());
                            if (why != null) {
                                events.add(SimpleConditionEvent.violated(type, type.getName()
                                        + " depends on " + dependency.getName() + " " + why));
                            }
                        }
                    }
                })
                .because(IMPL_PACKAGE_BECAUSE)
                .allowEmptyShould(true);
    }

    /**
     * @param self the implementation the depending class is in, or null outside one
     * @return why rule 4 refuses the dependency, or null when it accepts it
     */
    private static String implementationDependency(String self, String dependencyPackage) {
        String target = implementation(dependencyPackage);
        if (target != null && !target.equals(self)) {
            return self == null
                    ? "in implementation " + target + " from outside it"
                    : "in implementation " + target + " from implementation " + self;
        }
        if (self != null && target == null && servicePackageOfOurs(dependencyPackage)) {
            return "in a service package from implementation " + self;
        }
        return null;
    }

    /**
     * The implementation a package belongs to: the package through the segment after the first
     * {@code impl} segment under {@code io.freedriver}, so {@code io.freedriver.sms.impl.twilio.http}
     * gives {@code io.freedriver.sms.impl.twilio}. A package that ends at {@code impl} names no
     * implementation, and neither does a package outside {@code io.freedriver}.
     *
     * @return the implementation's package, or null
     */
    static String implementation(String pkg) {
        if (!under(pkg, OURS)) {
            return null;
        }
        String[] segments = pkg.split("\\.");
        for (int i = 2; i < segments.length - 1; i++) {
            if ("impl".equals(segments[i])) {
                return String.join(".", java.util.Arrays.copyOfRange(segments, 0, i + 2));
            }
        }
        return null;
    }

    private static boolean servicePackageOfOurs(String pkg) {
        return under(pkg, OURS) && java.util.Arrays.asList(pkg.split("\\.")).contains("service");
    }

    private static DescribedPredicate<JavaClass> subjects(ModuleOwnership ownership, ModuleOwnership.Role role) {
        return DescribedPredicate.describe("are " + role.name().toLowerCase() + " main classes",
                type -> judged(type, ownership) && ownership.classify(type).is(role));
    }

    private static DescribedPredicate<JavaClass> mainClasses(ModuleOwnership ownership) {
        return DescribedPredicate.describe("are main classes of our modules", type -> judged(type, ownership));
    }

    static boolean judged(JavaClass type, ModuleOwnership ownership) {
        if ("package-info".equals(type.getSimpleName()) || type.isAnonymousClass()) {
            return false;
        }
        if (!ownership.overlaid(type) && testSource(type)) {
            return false;
        }
        ModuleOwnership.Owned owned = ownership.classify(type);
        return owned.kind() == ModuleOwnership.Kind.OURS;
    }

    private static boolean testSource(JavaClass type) {
        return type.getSource().map(source -> {
            String path = source.getUri().toString().replace('\\', '/');
            return path.contains("/target/test-classes/");
        }).orElse(false);
    }

    /**
     * Direct dependencies of the class and of the anonymous classes it holds, except a type that
     * appears only because an annotation names it: the annotation itself, or a member or
     * default-value type ArchUnit imports with the annotation. A field, parameter or method body
     * that uses the same type is kept. One line per target name. The class's own anonymous
     * classes, and an anonymous class's reference to the class that holds it, are left out: an
     * anonymous class is part of its holder.
     */
    private static Set<JavaClass> dependencies(JavaClass type) {
        Map<String, List<Dependency>> byName = new LinkedHashMap<>();
        Map<String, JavaClass> targets = new LinkedHashMap<>();
        for (JavaClass source : withAnonymous(type)) {
            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = unwrap(dependency.getTargetClass());
                if (target.getName().equals(type.getName()) || anonymousOf(type, target)) {
                    continue;
                }
                byName.computeIfAbsent(target.getName(), name -> new ArrayList<>()).add(dependency);
                targets.putIfAbsent(target.getName(), target);
            }
        }
        Set<JavaClass> found = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<Dependency>> entry : byName.entrySet()) {
            if (entry.getValue().stream().allMatch(ApiQualityRules::fromAnnotationOnly)) {
                continue;
            }
            found.add(targets.get(entry.getKey()));
        }
        return found;
    }

    /**
     * Raw interfaces of the class and of the anonymous classes it holds, one entry per name,
     * plus each functional interface a lambda or method reference in those class files targets.
     * The line names this class. An anonymous class stays out of the subjects, and a second
     * implementation of the same interface, including an enum constant body or another lambda,
     * adds no second line.
     *
     * <p>A lambda or method reference that cannot be read fails the check with {@code type}'s name.
     * The failure is an error, so a frozen store cannot store it as a line.
     */
    private static List<JavaClass> implementedInterfaces(JavaClass type, Map<String, JavaClass> resolved) {
        Map<String, JavaClass> found = new LinkedHashMap<>();
        for (JavaClass source : withAnonymous(type)) {
            for (JavaClass iface : source.getAllRawInterfaces()) {
                found.putIfAbsent(iface.getName(), iface);
            }
            for (String name : LambdaSites.interfacesOf(type, source)) {
                if (platform(name)) {
                    continue;
                }
                JavaClass target = resolveInterface(type, name, resolved);
                found.putIfAbsent(target.getName(), target);
                for (JavaClass raw : target.getAllRawInterfaces()) {
                    if (!platform(raw.getName())) {
                        found.putIfAbsent(raw.getName(), raw);
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** A JDK interface does not extend an API interface, and ArchUnit cannot import a {@code jrt} class. */
    private static boolean platform(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("jdk.");
    }

    /** The functional interface named by a lambda call site, imported when this evaluation has not seen it. */
    private static JavaClass resolveInterface(JavaClass holder, String name, Map<String, JavaClass> resolved) {
        JavaClass known = resolved.get(name);
        if (known != null) {
            return known;
        }
        try {
            Class<?> reflected = Class.forName(name, false, classLoader());
            JavaClass imported = new ClassFileImporter().importClass(reflected);
            resolved.put(name, imported);
            return imported;
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            throw new IllegalStateException(holder.getName()
                    + " has a lambda bootstrap that cannot be decoded to " + name, e);
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            return context;
        }
        return ApiQualityRules.class.getClassLoader();
    }

    /** The class plus each anonymous class whose nearest non-anonymous encloser it is. */
    private static List<JavaClass> withAnonymous(JavaClass type) {
        List<JavaClass> sources = new ArrayList<>();
        sources.add(type);
        sources.addAll(anonymousClasses(type));
        return sources;
    }

    /**
     * Anonymous classes whose holding class is {@code type}: the nearest enclosing class that is
     * not itself anonymous. A member or local class keeps the anonymous classes written inside it.
     */
    static List<JavaClass> anonymousClasses(JavaClass type) {
        Map<String, JavaClass> found = new LinkedHashMap<>();
        String name = type.getName();
        for (JavaClass candidate : type.getPackage().getClasses()) {
            if (candidate.isAnonymousClass() && name.equals(holdingClass(candidate).getName())) {
                found.put(candidate.getName(), candidate);
            }
        }
        walkAnonymous(type, type, found, new HashSet<>());
        return new ArrayList<>(found.values());
    }

    /** Follows anonymous-class targets so a nested one is kept when the package scan misses it. */
    private static void walkAnonymous(JavaClass holder, JavaClass source, Map<String, JavaClass> found,
                                      Set<String> seen) {
        if (!seen.add(source.getName())) {
            return;
        }
        for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
            JavaClass target = dependency.getTargetClass();
            if (!target.isAnonymousClass() || !holder.getName().equals(holdingClass(target).getName())) {
                continue;
            }
            found.putIfAbsent(target.getName(), target);
            walkAnonymous(holder, target, found, seen);
        }
    }

    /** The nearest enclosing class that is not anonymous, or {@code type} when it is that class. */
    static JavaClass holdingClass(JavaClass type) {
        JavaClass current = type;
        while (current.isAnonymousClass()) {
            if (current.getEnclosingClass().isEmpty()) {
                return current;
            }
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    private static boolean anonymousOf(JavaClass holder, JavaClass target) {
        return target.isAnonymousClass() && holder.getName().equals(holdingClass(target).getName());
    }

    /** ArchUnit's description for an annotation type or one of its member and default-value types. */
    private static boolean fromAnnotationOnly(Dependency dependency) {
        String description = dependency.getDescription();
        return description.contains("is annotated with") || description.contains("has annotation member of type");
    }

    private static JavaClass unwrap(JavaClass type) {
        JavaClass current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        return current;
    }

    /**
     * @return why the dependency is refused, or null when the allowlist accepts it
     */
    private static String serviceDependency(JavaClass type, JavaClass dependency, ModuleOwnership.Owned self,
                                            ModuleOwnership ownership) {
        if (dependency.isPrimitive()) {
            return null;
        }
        String pkg = dependency.getPackageName();
        if (panache(pkg)) {
            return "is Panache";
        }
        if (dependency.isAnnotation() || servicePackage(pkg) || under(pkg, "io.micrometer")) {
            return null;
        }
        if (httpEdge(type) && (under(pkg, "io.vertx.core") || under(pkg, "io.vertx.ext.web"))) {
            return null;
        }
        if (mcpEdge(type) && under(pkg, "io.quarkiverse.mcp.server")) {
            return null;
        }
        return ownedDependency(dependency, self, ownership, ModuleOwnership.Role.SERVICE);
    }

    /**
     * The class's own markers. A nested class is judged on its own method, parameter or interface.
     */
    private static boolean httpEdge(JavaClass type) {
        return observes(type, "io.quarkus.vertx.http.runtime.filters.Filters")
                || observes(type, "io.vertx.ext.web.Router")
                || methodAnnotated(type, "io.quarkus.vertx.web.Route")
                || methodAnnotated(type, "io.quarkus.vertx.web.RouteFilter")
                || implementsPackage(type, "io.quarkus.oidc");
    }

    private static boolean mcpEdge(JavaClass type) {
        return methodAnnotatedFrom(type, "io.quarkiverse.mcp.server")
                || implementsPackage(type, "io.quarkiverse.mcp.server");
    }

    private static boolean observes(JavaClass type, String parameterType) {
        for (JavaMethod method : type.getMethods()) {
            for (JavaParameter parameter : method.getParameters()) {
                if (parameter.getRawType().getName().equals(parameterType) && observes(parameter)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean observes(JavaParameter parameter) {
        for (JavaAnnotation<?> annotation : parameter.getAnnotations()) {
            if ("jakarta.enterprise.event.Observes".equals(annotation.getRawType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodAnnotated(JavaClass type, String annotation) {
        for (JavaMethod method : type.getMethods()) {
            if (method.isAnnotatedWith(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodAnnotatedFrom(JavaClass type, String packageRoot) {
        for (JavaMethod method : type.getMethods()) {
            for (JavaAnnotation<?> annotation : method.getAnnotations()) {
                if (under(annotation.getRawType().getPackageName(), packageRoot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean implementsPackage(JavaClass type, String packageRoot) {
        for (JavaClass iface : type.getAllRawInterfaces()) {
            if (under(iface.getPackageName(), packageRoot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A record, enum, interface, exception, or permitted subtype of a sealed interface. A constant
     * holder stays out, so rule 3 still flags it.
     */
    private static boolean apiContractType(JavaClass type) {
        if (type.isInterface() || type.isRecord() || type.isEnum() || type.isAssignableTo(Throwable.class)) {
            return true;
        }
        return permittedSubtype(type);
    }

    /**
     * ArchUnit's {@link JavaClass} has no sealed or permits query. One direct interface reflects to
     * a {@link Class} whose {@link Class#isSealed()} is true and whose
     * {@link Class#getPermittedSubclasses()} contains this type.
     */
    private static boolean permittedSubtype(JavaClass type) {
        for (JavaClass iface : type.getRawInterfaces()) {
            Class<?> reflected = reflect(iface);
            if (reflected == null || !reflected.isSealed()) {
                continue;
            }
            Class<?>[] permitted = reflected.getPermittedSubclasses();
            if (permitted == null) {
                continue;
            }
            for (Class<?> candidate : permitted) {
                if (candidate.getName().equals(type.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    static Class<?> reflect(JavaClass type) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader own = ApiQualityRules.class.getClassLoader();
        for (ClassLoader loader : new ClassLoader[] {context, own}) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(type.getName(), false, loader);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // The next loader may be the one that defined the sealed interface.
            }
        }
        try {
            return type.reflect();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String apiDependency(JavaClass dependency, ModuleOwnership.Owned self, ModuleOwnership ownership) {
        if (dependency.isPrimitive()) {
            return null;
        }
        String pkg = dependency.getPackageName();
        if (dependency.isAnnotation() || apiPackage(pkg)) {
            return null;
        }
        return ownedDependency(dependency, self, ownership, ModuleOwnership.Role.API);
    }

    /**
     * API code is accepted from any module. Service code may also use the utility modules and the
     * service code of its own module. Everything else of ours is refused: another module's service
     * code, any implementation, and, for an API class, the service code beside it.
     *
     * @param subject the role of the depending class, SERVICE for rule 1 and API for rule 2
     */
    private static String ownedDependency(JavaClass dependency, ModuleOwnership.Owned self,
                                          ModuleOwnership ownership, ModuleOwnership.Role subject) {
        ModuleOwnership.Owned owned = ownership.classify(dependency);
        if (owned.kind() == ModuleOwnership.Kind.UNRESOLVED) {
            return "is unresolved";
        }
        if (owned.kind() == ModuleOwnership.Kind.THIRD_PARTY) {
            return "is a third-party library";
        }
        if (owned.is(ModuleOwnership.Role.API)) {
            return null;
        }
        boolean service = subject == ModuleOwnership.Role.SERVICE;
        if (service && owned.is(ModuleOwnership.Role.UTILITY)) {
            return null;
        }
        if (!owned.sameModule(self)) {
            return "is " + owned.detail();
        }
        if (service && owned.is(ModuleOwnership.Role.SERVICE)) {
            return null;
        }
        return owned.is(ModuleOwnership.Role.SERVICE)
                ? "is service code of its own module"
                : "is implementation code of its own module";
    }

    private static boolean servicePackage(String pkg) {
        return under(pkg, "java") || under(pkg, "javax") || under(pkg, "jakarta")
                || under(pkg, "io.quarkus") || under(pkg, "io.smallrye")
                || under(pkg, "org.eclipse.microprofile");
    }

    private static boolean apiPackage(String pkg) {
        return under(pkg, "java") || under(pkg, "javax") || under(pkg, "jakarta")
                || under(pkg, "org.eclipse.microprofile");
    }

    static boolean under(String pkg, String root) {
        return pkg.equals(root) || pkg.startsWith(root + ".");
    }

    private static boolean panache(String pkg) {
        return pkg.startsWith("io.quarkus.mongodb.panache") || pkg.startsWith("io.quarkus.hibernate.orm.panache");
    }

    private static boolean contractShape(JavaClass type) {
        if (type.isAnnotation() || type.isInterface() || type.isRecord() || type.isEnum()) {
            return true;
        }
        if (throwableType(type)) {
            return true;
        }
        return constantHolder(type);
    }

    /**
     * An exception is a contract shape. ArchUnit's {@code isAssignableTo(Throwable)} is false when
     * the JDK hierarchy was not imported, so the superclass name and, failing that, reflection decide.
     */
    static boolean throwableType(JavaClass type) {
        if (type.isAssignableTo(Throwable.class)) {
            return true;
        }
        JavaClass current = type;
        for (int hop = 0; hop < 8 && current != null; hop++) {
            String name = current.getName();
            if ("java.lang.Throwable".equals(name) || "java.lang.Exception".equals(name)
                    || "java.lang.Error".equals(name) || "java.lang.RuntimeException".equals(name)) {
                return true;
            }
            current = current.getRawSuperclass().orElse(null);
        }
        Class<?> reflected = reflect(type);
        return reflected != null && Throwable.class.isAssignableFrom(reflected);
    }

    /** The PendingCapHeader and VerifyFailure shape: static final fields and one private constructor. */
    private static boolean constantHolder(JavaClass type) {
        if (!type.getModifiers().contains(JavaModifier.FINAL) || type.isAnonymousClass() || type.isMemberClass()) {
            return false;
        }
        for (JavaField field : type.getFields()) {
            Set<JavaModifier> modifiers = field.getModifiers();
            if (!modifiers.contains(JavaModifier.STATIC) || !modifiers.contains(JavaModifier.FINAL)) {
                return false;
            }
        }
        if (!type.getMethods().isEmpty()) {
            return false;
        }
        if (type.getConstructors().size() != 1) {
            return false;
        }
        JavaConstructor constructor = type.getConstructors().iterator().next();
        return constructor.getModifiers().contains(JavaModifier.PRIVATE);
    }

    private static boolean cdiScope(JavaClass type) {
        for (JavaAnnotation<?> annotation : type.getAnnotations()) {
            if (RoleRules.CDI_SCOPES.contains(annotation.getRawType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean jaxRsContract(JavaClass iface) {
        if (jaxRs(iface.getAnnotations())) {
            return true;
        }
        for (JavaMethod method : iface.getMethods()) {
            if (jaxRs(method.getAnnotations())) {
                return true;
            }
            for (JavaParameter parameter : method.getParameters()) {
                if (jaxRs(parameter.getAnnotations())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jaxRs(Iterable<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (annotation.getRawType().getPackageName().startsWith("jakarta.ws.rs")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Functional interfaces implemented by a lambda or a method reference in one class file.
     * ArchUnit keeps the implementation handle of an invokedynamic and drops the call-site
     * descriptor, so the interface is read here from the class file with ASM.
     * {@code holder} is the class the line names. {@code source} is that class or an anonymous
     * class it holds. A class file that cannot be read, or a lambda bootstrap that cannot be
     * decoded, fails with {@code holder}'s name.
     */
    private static final class LambdaSites extends ClassVisitor {

        private static final String FACTORY = "java/lang/invoke/LambdaMetafactory";
        private static final String METAFACTORY = "metafactory";
        private static final String ALT_METAFACTORY = "altMetafactory";
        private static final String METAFACTORY_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;"
                + "Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
        private static final String ALT_DESC = "(Ljava/lang/invoke/MethodHandles$Lookup;"
                + "Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)"
                + "Ljava/lang/invoke/CallSite;";

        private final Set<String> names = new LinkedHashSet<>();

        private LambdaSites() {
            super(Opcodes.ASM9);
        }

        static List<String> interfacesOf(JavaClass holder, JavaClass source) {
            byte[] bytes;
            try {
                bytes = bytesOf(source);
            } catch (IOException | RuntimeException e) {
                throw unreadable(holder, e);
            }
            try {
                LambdaSites sites = new LambdaSites();
                new ClassReader(bytes).accept(sites, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return List.copyOf(sites.names);
            } catch (DecodeFailure e) {
                throw new IllegalStateException(holder.getName()
                        + " has a lambda bootstrap that cannot be decoded: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                throw unreadable(holder, e);
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                          String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitInvokeDynamicInsn(String invokeName, String invokeDescriptor, Handle bootstrap,
                                                    Object... bootstrapArguments) {
                    if (!FACTORY.equals(bootstrap.getOwner())) {
                        return;
                    }
                    if (METAFACTORY.equals(bootstrap.getName())) {
                        if (!METAFACTORY_DESC.equals(bootstrap.getDesc())) {
                            throw new DecodeFailure("metafactory descriptor");
                        }
                        metafactory(invokeDescriptor, bootstrapArguments);
                        return;
                    }
                    if (ALT_METAFACTORY.equals(bootstrap.getName())) {
                        if (!ALT_DESC.equals(bootstrap.getDesc())) {
                            throw new DecodeFailure("altMetafactory descriptor");
                        }
                        altMetafactory(invokeDescriptor, bootstrapArguments);
                    }
                }
            };
        }

        private void metafactory(String descriptor, Object[] arguments) {
            if (arguments.length != 3 || !(arguments[0] instanceof Type) || !(arguments[1] instanceof Handle)
                    || !(arguments[2] instanceof Type)) {
                throw new DecodeFailure("metafactory arguments");
            }
            names.add(returnInterface(descriptor));
        }

        private void altMetafactory(String descriptor, Object[] arguments) {
            if (arguments.length < 4 || !(arguments[0] instanceof Type) || !(arguments[1] instanceof Handle)
                    || !(arguments[2] instanceof Type) || !(arguments[3] instanceof Integer)) {
                throw new DecodeFailure("altMetafactory arguments");
            }
            names.add(returnInterface(descriptor));
            int flags = (Integer) arguments[3];
            int index = 4;
            if ((flags & LambdaMetafactory.FLAG_MARKERS) != 0) {
                index = markers(arguments, index);
            }
            if ((flags & LambdaMetafactory.FLAG_BRIDGES) != 0) {
                index = bridges(arguments, index);
            }
            if (index != arguments.length) {
                throw new DecodeFailure("altMetafactory arguments");
            }
        }

        private int markers(Object[] arguments, int index) {
            if (index >= arguments.length || !(arguments[index] instanceof Integer)) {
                throw new DecodeFailure("marker interfaces");
            }
            int count = (Integer) arguments[index];
            index++;
            if (count < 0 || index + count > arguments.length) {
                throw new DecodeFailure("marker interfaces");
            }
            for (int i = 0; i < count; i++) {
                names.add(interfaceName(arguments[index]));
                index++;
            }
            return index;
        }

        private static int bridges(Object[] arguments, int index) {
            if (index >= arguments.length || !(arguments[index] instanceof Integer)) {
                throw new DecodeFailure("bridge methods");
            }
            int count = (Integer) arguments[index];
            index++;
            if (count < 0 || index + count > arguments.length) {
                throw new DecodeFailure("bridge methods");
            }
            return index + count;
        }

        private static String returnInterface(String descriptor) {
            Type returned;
            try {
                returned = Type.getReturnType(descriptor);
            } catch (RuntimeException e) {
                throw new DecodeFailure("call site descriptor");
            }
            if (returned.getSort() != Type.OBJECT) {
                throw new DecodeFailure("call site return type");
            }
            return returned.getClassName();
        }

        private static String interfaceName(Object argument) {
            if (argument instanceof Type type && type.getSort() == Type.OBJECT) {
                return type.getClassName();
            }
            throw new DecodeFailure("marker interface");
        }

        private static byte[] bytesOf(JavaClass source) throws IOException {
            if (source.getSource().isEmpty()) {
                throw new IOException("class file has no source");
            }
            return bytes(source.getSource().get().getUri());
        }

        private static byte[] bytes(URI uri) throws IOException {
            if (uri == null) {
                throw new IOException("class file has no source");
            }
            if ("jar".equalsIgnoreCase(uri.getScheme())) {
                return jarBytes(uri);
            }
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Files.readAllBytes(Path.of(uri));
            }
            throw new IOException("unsupported class file source");
        }

        private static byte[] jarBytes(URI uri) throws IOException {
            String specific = uri.getSchemeSpecificPart();
            int bang = specific.indexOf('!');
            if (bang < 0) {
                throw new IOException("jar source has no entry");
            }
            String entry = specific.substring(bang + 1);
            if (entry.startsWith("/")) {
                entry = entry.substring(1);
            }
            try (JarFile jar = new JarFile(Path.of(URI.create(specific.substring(0, bang))).toFile())) {
                JarEntry found = jar.getJarEntry(entry);
                if (found == null) {
                    throw new IOException("jar entry is missing");
                }
                try (InputStream in = jar.getInputStream(found)) {
                    return in.readAllBytes();
                }
            }
        }

        private static IllegalStateException unreadable(JavaClass holder, Exception cause) {
            return new IllegalStateException(holder.getName() + " cannot be read: " + cause.getClass().getSimpleName(),
                    cause);
        }

        private static final class DecodeFailure extends IllegalStateException {
            private DecodeFailure(String reason) {
                super(reason);
            }
        }
    }
}
