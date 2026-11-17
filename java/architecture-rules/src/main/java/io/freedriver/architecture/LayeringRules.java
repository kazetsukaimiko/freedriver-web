package io.freedriver.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.function.Predicate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The Java layering rules of freedriver-web (#560), one rule per row of the ticket's table. Each
 * rule's reason names the fix. {@link LayeringTest} runs them, frozen, against one module.
 */
public final class LayeringRules {

    static final String SECURITY_IDENTITY = "io.quarkus.security.identity.SecurityIdentity";
    static final String RATE_LIMIT = "io.smallrye.faulttolerance.api.RateLimit";
    static final String RESPONSE = "jakarta.ws.rs.core.Response";
    /** The marker that lets a resource method return {@code Response} (a redirect, a stream). */
    public static final String RETURNS_RESPONSE = "ReturnsResponse";

    private LayeringRules() {
    }

    /** Row 1: JAX-RS types, exceptions included, appear only at the HTTP edge. */
    public static ArchRule jaxRsStaysAtTheEdge() {
        return noClasses().that(not(enclosedBy(LayeringRules::jaxRsEdge, "the JAX-RS edge")))
                .should().dependOnClassesThat().resideInAPackage("jakarta.ws.rs..")
                .because("JAX-RS belongs at the edge: a *Resource, *Api, *Filter, *Mapper(s), @Provider or "
                        + "REST client interface. Throw a domain exception and map it to a status in the "
                        + "module's ErrorMappers, or move the HTTP type into an edge class");
    }

    /** Row 2a: every XxxResource implements XxxApi. */
    public static ArchRule resourcesImplementTheirApi() {
        return classes().that(LayeringRules.resourceClasses())
                .should(implementTheirApi())
                .because("the XxxApi interface is the contract: declare the endpoints on XxxApi and "
                        + "let XxxResource implement it")
                .allowEmptyShould(true);
    }

    /** Row 2b: JAX-RS and Bean Validation annotations sit on the interface, not on the resource. */
    public static ArchRule resourcesCarryNoContractAnnotations() {
        return classes().that(LayeringRules.resourceClasses())
                .should(carryNoContractAnnotations())
                .because("the XxxApi interface carries every JAX-RS and Bean Validation annotation; move "
                        + "the annotation from XxxResource to the method or parameter on XxxApi")
                .allowEmptyShould(true);
    }

    /** Row 3: resource methods return DTOs, except a method marked {@code @ReturnsResponse}. */
    public static ArchRule resourceMethodsReturnDtos() {
        return methods().that().areDeclaredInClassesThat(LayeringRules.resourceClasses())
                .and().arePublic()
                .and(DescribedPredicate.describe("are not marked @" + RETURNS_RESPONSE,
                        (JavaMethod m) -> m.getAnnotations().stream()
                                .noneMatch(a -> a.getRawType().getSimpleName().equals(RETURNS_RESPONSE))))
                .should().notHaveRawReturnType(RESPONSE)
                .because("a resource method returns a typed DTO and throws a domain exception that an "
                        + "ExceptionMapper turns into a status; a redirect or stream that needs Response "
                        + "carries @" + RETURNS_RESPONSE + " with its reason")
                .allowEmptyShould(true);
    }

    /** Row 4: SecurityIdentity stays at the edge. */
    public static ArchRule securityIdentityStaysAtTheEdge() {
        return noClasses().that(not(enclosedBy(LayeringRules::securityEdge, "the security edge")))
                .should().dependOnClassesThat().haveFullyQualifiedName(SECURITY_IDENTITY)
                .because("the identity is read at the edge (a *Resource, *Filter, *Feature, MCP *Tools "
                        + "class, identity augmentor, authentication mechanism, tenant resolver or tool "
                        + "filter); pass the service the subject or roles it needs as plain values");
    }

    /** Row 5: service unit tests do not import JAX-RS. Run against the test classes. */
    public static ArchRule serviceUnitTestsAvoidJaxRs() {
        return noClasses().that(DescribedPredicate.describe("are service unit tests", LayeringRules::serviceUnitTest))
                .should().dependOnClassesThat().resideInAPackage("jakarta.ws.rs..")
                .because("a service unit test calls the service and asserts its result or domain "
                        + "exception; HTTP status belongs in the resource's @QuarkusTest");
    }

    /** Row 6: SmallRye {@code @RateLimit} is not used. */
    public static ArchRule noSmallRyeRateLimit() {
        return noClasses().should().dependOnClassesThat().haveFullyQualifiedName(RATE_LIMIT)
                .because("SmallRye @RateLimit is one bucket per bean method, not per caller; use the "
                        + "module's per-caller limiter (@RateLimited and the HitWindow)");
    }

    /** Row 7: domain exceptions live in one package per module. */
    public static ArchRule exceptionsLiveIn(String exceptionPackage) {
        return classes().that().areAssignableTo(Throwable.class)
                .and(not(enclosedBy(c -> c.getSimpleName().endsWith("Mapper") || c.getSimpleName().endsWith("Mappers"),
                        "an exception mapper")))
                .should().resideInAPackage(exceptionPackage)
                .because("the module keeps its domain exceptions in one package, " + exceptionPackage
                        + "; move the exception class there")
                .allowEmptyShould(true);
    }

    /** Row 8: Mongo only from repository classes. */
    public static ArchRule mongoOnlyFromRepositories() {
        return noClasses().that(not(enclosedBy(LayeringRules::repository, "a repository")))
                .should().dependOnClassesThat().resideInAPackage("com.mongodb.client..")
                .because("Mongo access goes through a *Repository (a PanacheMongoRepository); call the "
                        + "repository instead of the Mongo driver");
    }

    /** Row 9: no HTTP client, no Jedis, Lettuce or Redisson, no hand-built Keycloak admin client. */
    public static ArchRule noForeignClients() {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.net.http..", "okhttp3..", "org.apache.http..", "org.apache.hc..",
                        "io.vertx.ext.web.client..", "redis.clients.jedis..", "io.lettuce..", "org.redisson..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.keycloak.admin.client.KeycloakBuilder")
                .because("outgoing HTTP uses a REST client interface (or sms-client, device-daemon), Redis "
                        + "uses the Quarkus Redis client, and the Keycloak admin client is the one the "
                        + "Quarkus extension injects");
    }

    static boolean jaxRsEdge(JavaClass c) {
        String n = c.getSimpleName();
        return n.endsWith("Resource") || n.endsWith("Api") || n.endsWith("Filter")
                || n.endsWith("Mapper") || n.endsWith("Mappers")
                || c.isAnnotatedWith("org.eclipse.microprofile.rest.client.inject.RegisterRestClient")
                || c.isAnnotatedWith("jakarta.ws.rs.ext.Provider");
    }

    static boolean securityEdge(JavaClass c) {
        String n = c.getSimpleName();
        return n.endsWith("Resource") || n.endsWith("Filter") || n.endsWith("Feature") || n.endsWith("Tools")
                || c.isAnnotatedWith("jakarta.ws.rs.ext.Provider")
                || c.isAssignableTo("io.quarkus.security.identity.SecurityIdentityAugmentor")
                || c.isAssignableTo("io.quarkus.security.identity.IdentityProvider")
                || c.isAssignableTo("io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism")
                || c.isAssignableTo("io.quarkus.oidc.TenantResolver")
                || c.isAssignableTo("io.quarkiverse.mcp.server.ToolFilter");
    }

    static boolean repository(JavaClass c) {
        return c.getSimpleName().endsWith("Repository")
                || c.isAssignableTo("io.quarkus.mongodb.panache.PanacheMongoRepositoryBase");
    }

    static boolean serviceUnitTest(JavaClass c) {
        String n = c.getSimpleName();
        if (!n.endsWith("Test") || c.isAnnotatedWith("io.quarkus.test.junit.QuarkusTest")
                || c.isAnnotatedWith("io.quarkus.test.junit.QuarkusIntegrationTest")) {
            return false;
        }
        String subject = n.substring(0, n.length() - "Test".length());
        return !(subject.endsWith("Resource") || subject.endsWith("Api") || subject.endsWith("Filter")
                || subject.endsWith("Mapper") || subject.endsWith("Mappers") || subject.endsWith("Feature"));
    }

    private static DescribedPredicate<JavaClass> resourceClasses() {
        return DescribedPredicate.describe("are XxxResource classes",
                c -> !c.isInterface() && c.getEnclosingClass().isEmpty() && c.getSimpleName().endsWith("Resource"));
    }

    private static DescribedPredicate<JavaClass> enclosedBy(Predicate<JavaClass> test, String description) {
        return DescribedPredicate.describe("are " + description + " or inside one", c -> {
            for (JavaClass k = c; k != null; k = k.getEnclosingClass().orElse(null)) {
                if (test.test(k)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static <T> DescribedPredicate<T> not(DescribedPredicate<T> p) {
        return DescribedPredicate.not(p);
    }

    private static ArchCondition<JavaClass> implementTheirApi() {
        return new ArchCondition<>("implement the XxxApi interface named after them") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                String api = c.getSimpleName().replaceFirst("Resource$", "Api");
                boolean found = c.getAllRawInterfaces().stream().anyMatch(i -> i.getSimpleName().equals(api));
                if (!found) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " implements no " + api));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> carryNoContractAnnotations() {
        return new ArchCondition<>("carry no JAX-RS or Bean Validation annotation") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                report(c.getName(), c.getAnnotations(), c, events);
                for (JavaMethod m : c.getMethods()) {
                    report(m.getFullName(), m.getAnnotations(), c, events);
                    for (JavaParameter p : m.getParameters()) {
                        report(m.getFullName() + " parameter " + p.getIndex(), p.getAnnotations(), c, events);
                    }
                }
            }

            private void report(String where, java.util.Collection<? extends JavaAnnotation<?>> annotations,
                                JavaClass owner, ConditionEvents events) {
                for (JavaAnnotation<?> a : annotations) {
                    JavaClass type = a.getRawType();
                    String pkg = type.getPackageName();
                    if (pkg.startsWith("jakarta.ws.rs") || pkg.startsWith("jakarta.validation")
                            || type.isAnnotatedWith("jakarta.validation.Constraint")) {
                        events.add(SimpleConditionEvent.violated(owner, where + " has @" + type.getSimpleName()));
                    }
                }
            }
        };
    }
}
