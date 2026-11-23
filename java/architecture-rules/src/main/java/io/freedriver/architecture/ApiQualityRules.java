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
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * Rules 1 to 3 of the API quality gate. Each rule judges main classes only. {@link ApiQuality}
 * freezes them per module.
 */
public final class ApiQualityRules {

    static final String REGISTER_REST_CLIENT = "org.eclipse.microprofile.rest.client.inject.RegisterRestClient";

    private static final String SERVICE_BECAUSE = "service code depends only on java, javax, jakarta, "
            + "io.quarkus except io.quarkus.mongodb.panache and io.quarkus.hibernate.orm.panache, "
            + "io.smallrye, org.eclipse.microprofile, annotation types, its own module, an API module "
            + "(artifactId ending -api, -model or -contract) and the utility artifactIds stereotypes, "
            + "mcp-support, hit-window and freedriver-mqtt";

    private static final String API_BECAUSE = "an API module holds interfaces, records, enums, exceptions, "
            + "annotations and final constant holders (only static final fields and a private constructor), "
            + "carries no CDI scope, and depends only on java, javax, jakarta, org.eclipse.microprofile, "
            + "annotation types and API modules";

    private static final String IMPL_BECAUSE = "a main class that implements an interface from an API module "
            + "lives in an artifactId ending -impl; a JAX-RS resource may implement its JAX-RS API interface, "
            + "and a generated MicroProfile REST client is exempt";

    private ApiQualityRules() {
    }

    /** Rule 1. Service modules depend on the allowlist. */
    public static ArchRule serviceDependencies() {
        return serviceDependencies(ModuleOwnership.reactor());
    }

    /** Rule 2. API modules stay contracts. */
    public static ArchRule apiContracts() {
        return apiContracts(ModuleOwnership.reactor());
    }

    /** Rule 3. A class that implements an API interface lives in an {@code -impl} module. */
    public static ArchRule implementationsLiveInImpl() {
        return implementationsLiveInImpl(ModuleOwnership.reactor());
    }

    static ArchRule serviceDependencies(ModuleOwnership ownership) {
        return classes().that(subjects(ownership, ModuleOwnership.Role.SERVICE))
                .should(new ArchCondition<>("depend only on the service allowlist") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        ModuleOwnership.Owned self = ownership.classify(type);
                        for (JavaClass dependency : dependencies(type)) {
                            String why = serviceDependency(dependency, self, ownership);
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
                    }
                })
                .because(API_BECAUSE)
                .allowEmptyShould(true);
    }

    static ArchRule implementationsLiveInImpl(ModuleOwnership ownership) {
        return classes().that(mainClasses(ownership))
                .should(new ArchCondition<>("live in an -impl module when they implement an API interface") {
                    @Override
                    public void check(JavaClass type, ConditionEvents events) {
                        if (type.isAnnotatedWith(REGISTER_REST_CLIENT)) {
                            return;
                        }
                        ModuleOwnership.Owned self = ownership.classify(type);
                        if (self.kind() == ModuleOwnership.Kind.OURS && self.artifactId().endsWith("-impl")) {
                            return;
                        }
                        for (JavaClass iface : type.getAllRawInterfaces()) {
                            ModuleOwnership.Owned owner = ownership.classify(iface);
                            if (owner.kind() != ModuleOwnership.Kind.OURS
                                    || owner.role() != ModuleOwnership.Role.API) {
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

    private static DescribedPredicate<JavaClass> subjects(ModuleOwnership ownership, ModuleOwnership.Role role) {
        return DescribedPredicate.describe("are " + role.name().toLowerCase() + " main classes",
                type -> judged(type, ownership) && ownership.classify(type).role() == role);
    }

    private static DescribedPredicate<JavaClass> mainClasses(ModuleOwnership ownership) {
        return DescribedPredicate.describe("are main classes of our modules", type -> judged(type, ownership));
    }

    private static boolean judged(JavaClass type, ModuleOwnership ownership) {
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
     * Direct dependencies, except a type that appears only because an annotation names it: the
     * annotation itself, or a member or default-value type ArchUnit imports with the annotation.
     * A field, parameter or method body that uses the same type is kept.
     */
    private static Set<JavaClass> dependencies(JavaClass type) {
        java.util.Map<String, java.util.List<Dependency>> byName = new java.util.LinkedHashMap<>();
        java.util.Map<String, JavaClass> targets = new java.util.LinkedHashMap<>();
        for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
            JavaClass target = unwrap(dependency.getTargetClass());
            if (target.getName().equals(type.getName())) {
                continue;
            }
            byName.computeIfAbsent(target.getName(), name -> new java.util.ArrayList<>()).add(dependency);
            targets.putIfAbsent(target.getName(), target);
        }
        java.util.LinkedHashSet<JavaClass> found = new java.util.LinkedHashSet<>();
        for (java.util.Map.Entry<String, java.util.List<Dependency>> entry : byName.entrySet()) {
            if (entry.getValue().stream().allMatch(ApiQualityRules::fromAnnotationOnly)) {
                continue;
            }
            found.add(targets.get(entry.getKey()));
        }
        return found;
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
    private static String serviceDependency(JavaClass dependency, ModuleOwnership.Owned self, ModuleOwnership ownership) {
        if (dependency.isPrimitive()) {
            return null;
        }
        String pkg = dependency.getPackageName();
        if (panache(pkg)) {
            return "is Panache";
        }
        if (dependency.isAnnotation() || servicePackage(pkg)) {
            return null;
        }
        return ownedDependency(dependency, self, ownership, true);
    }

    private static String apiDependency(JavaClass dependency, ModuleOwnership.Owned self, ModuleOwnership ownership) {
        if (dependency.isPrimitive()) {
            return null;
        }
        String pkg = dependency.getPackageName();
        if (dependency.isAnnotation() || apiPackage(pkg)) {
            return null;
        }
        return ownedDependency(dependency, self, ownership, false);
    }

    private static String ownedDependency(JavaClass dependency, ModuleOwnership.Owned self,
                                          ModuleOwnership ownership, boolean utilityAllowed) {
        ModuleOwnership.Owned owned = ownership.classify(dependency);
        if (owned.kind() == ModuleOwnership.Kind.UNRESOLVED) {
            return "is unresolved";
        }
        if (owned.kind() == ModuleOwnership.Kind.OURS && owned.sameModule(self)) {
            return null;
        }
        if (owned.kind() == ModuleOwnership.Kind.OURS && owned.role() == ModuleOwnership.Role.API) {
            return null;
        }
        if (utilityAllowed && owned.kind() == ModuleOwnership.Kind.OURS
                && owned.role() == ModuleOwnership.Role.UTILITY) {
            return null;
        }
        if (owned.kind() == ModuleOwnership.Kind.THIRD_PARTY) {
            return "is a third-party library";
        }
        return "is " + owned.detail();
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

    private static boolean under(String pkg, String root) {
        return pkg.equals(root) || pkg.startsWith(root + ".");
    }

    private static boolean panache(String pkg) {
        return pkg.startsWith("io.quarkus.mongodb.panache") || pkg.startsWith("io.quarkus.hibernate.orm.panache");
    }

    private static boolean contractShape(JavaClass type) {
        if (type.isAnnotation() || type.isInterface() || type.isRecord() || type.isEnum()) {
            return true;
        }
        if (type.isAssignableTo(Throwable.class)) {
            return true;
        }
        return constantHolder(type);
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
}
