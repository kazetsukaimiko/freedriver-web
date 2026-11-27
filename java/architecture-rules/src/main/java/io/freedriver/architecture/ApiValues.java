package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaConstructorReference;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "API types are values" checks rule 2 runs on a class that already has a contract shape.
 * A plain class keeps only its existing line. Messages name the class, the member and the reason,
 * and the class is the event object, so a store line carries no source line number.
 */
final class ApiValues {

    /** Appended to rule 2's because-text. The three existing violation messages stay as they are. */
    static final String BECAUSE = "API types are values. Every field is final. A static field is refused when its "
            + "declared type is an array, or when the static initializer constructs a mutable java.util collection "
            + "or a java.util.concurrent type other than TimeUnit, or directly calls a constructor or method of the "
            + "same class whose body, lambdas included, does. That is one call level, judged within the class, with "
            + "no data flow. An interface's static methods are factories that return the interface or a permitted "
            + "subtype. A type body may call a sealed API interface and any API interface it implements, and no other "
            + "non-sealed API interface. Value code, the calls, constructor calls and field accesses in method and "
            + "constructor bodies and static initializers, may use the API allowlist except the packages java.io, "
            + "java.nio, java.net, java.sql, javax.sql, javax.naming, java.lang.reflect, java.lang.invoke, "
            + "java.util.concurrent, java.util.logging, java.util.regex, jakarta.ws.rs.client and "
            + "org.eclipse.microprofile.rest.client and everything under them, the classes java.lang.System, "
            + "Runtime, Thread, ProcessBuilder, ClassLoader and java.util.ServiceLoader, and the reflective members "
            + "of java.lang.Class (forName, newInstance, getClassLoader, getResource and the get Method, Field and "
            + "Constructor families). java.net.URI, java.nio.charset.Charset, java.nio.charset.StandardCharsets and "
            + "java.util.concurrent.TimeUnit are allowed. Compiler-generated synthetic and bridge members, and an "
            + "enum's values() and valueOf(String), are not judged. A lambda is judged as the member that holds it. "
            + "These checks skip a class that is a plain class.";

    private static final Set<String> DENIED_PACKAGES = Set.of(
            "java.io", "java.nio", "java.net", "java.sql", "javax.sql", "javax.naming",
            "java.lang.reflect", "java.lang.invoke", "java.util.concurrent", "java.util.logging",
            "java.util.regex", "jakarta.ws.rs.client", "org.eclipse.microprofile.rest.client");

    private static final Set<String> DENIED_CLASSES = Set.of(
            "java.lang.System", "java.lang.Runtime", "java.lang.Thread", "java.lang.ProcessBuilder",
            "java.lang.ClassLoader", "java.util.ServiceLoader");

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "java.net.URI", "java.nio.charset.Charset", "java.nio.charset.StandardCharsets",
            "java.util.concurrent.TimeUnit");

    /**
     * Mutable {@code java.util} collections by name. ArchUnit does not resolve the JDK hierarchy
     * for a class it has not imported, so {@code isAssignableTo(Collection)} is false for
     * {@code ArrayList} in a fixture and in a module under test.
     */
    private static final Set<String> MUTABLE_COLLECTIONS = Set.of(
            "java.util.ArrayList", "java.util.LinkedList", "java.util.Vector", "java.util.Stack",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.ArrayDeque", "java.util.PriorityQueue", "java.util.HashMap",
            "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.WeakHashMap",
            "java.util.IdentityHashMap", "java.util.EnumMap", "java.util.Hashtable",
            "java.util.Properties", "java.util.EnumSet");

    private ApiValues() {
    }

    static void check(JavaClass type, ConditionEvents events, ModuleOwnership ownership) {
        Set<String> lines = new LinkedHashSet<>();
        for (JavaField field : type.getFields()) {
            if (generated(field)) {
                continue;
            }
            if (!field.getModifiers().contains(JavaModifier.FINAL)) {
                add(events, lines, type, "field " + field.getName(), "is not final");
            }
            if (field.getModifiers().contains(JavaModifier.STATIC) && field.getRawType().isArray()) {
                add(events, lines, type, "field " + field.getName(), "is a static array");
            }
        }
        if (type.isInterface()) {
            for (JavaMethod method : type.getMethods()) {
                if (skipMethod(type, method)) {
                    continue;
                }
                if (method.getModifiers().contains(JavaModifier.STATIC) && !factoryReturn(type, method)) {
                    add(events, lines, type, "method " + method.getName(),
                            "returns " + method.getRawReturnType().getName()
                                    + "; a static method returns the interface or a permitted subtype");
                }
            }
        }
        for (JavaCodeUnit unit : roots(type)) {
            for (JavaCodeUnit body : withBodies(type, unit)) {
                valueCode(type, unit, body, events, lines, ownership);
            }
        }
        staticValues(type, events, lines);
    }

    private static void staticValues(JavaClass type, ConditionEvents events, Set<String> lines) {
        if (type.getStaticInitializer().isEmpty()) {
            return;
        }
        JavaCodeUnit initializer = type.getStaticInitializer().get();
        List<JavaCodeUnit> bodies = withBodies(type, initializer);
        for (JavaCodeUnit body : bodies) {
            for (JavaClass constructed : constructions(body)) {
                if (mutableConstruction(constructed)) {
                    add(events, lines, type, "static initializer", "constructs " + constructed.getName());
                }
            }
        }
        Set<JavaCodeUnit> callees = new LinkedHashSet<>();
        for (JavaCodeUnit body : bodies) {
            sameClassCallees(type, body, callees);
        }
        for (JavaCodeUnit callee : callees) {
            for (JavaCodeUnit body : withBodies(type, callee)) {
                for (JavaClass constructed : constructions(body)) {
                    if (!mutableConstruction(constructed)) {
                        continue;
                    }
                    add(events, lines, type, "static initializer", "constructs " + constructed.getName());
                    add(events, lines, type, member(callee), "constructs " + constructed.getName());
                }
            }
        }
    }

    private static void valueCode(JavaClass type, JavaCodeUnit reported, JavaCodeUnit body,
                                  ConditionEvents events, Set<String> lines, ModuleOwnership ownership) {
        String member = member(reported);
        for (JavaMethodCall call : body.getMethodCallsFromSelf()) {
            String reason = callRefusal(type, call.getTarget().getOwner(), call.getTarget().getName(), ownership);
            if (reason != null) {
                add(events, lines, type, member, reason);
            }
        }
        for (JavaMethodReference reference : body.getMethodReferencesFromSelf()) {
            String reason = callRefusal(type, reference.getTarget().getOwner(), reference.getTarget().getName(),
                    ownership);
            if (reason != null) {
                add(events, lines, type, member, reason);
            }
        }
        for (JavaConstructorCall call : body.getConstructorCallsFromSelf()) {
            String reason = constructRefusal(call.getTarget().getOwner());
            if (reason != null) {
                add(events, lines, type, member, reason);
            }
        }
        for (JavaConstructorReference reference : body.getConstructorReferencesFromSelf()) {
            String reason = constructRefusal(reference.getTarget().getOwner());
            if (reason != null) {
                add(events, lines, type, member, reason);
            }
        }
        for (JavaFieldAccess access : body.getFieldAccesses()) {
            String reason = fieldRefusal(access.getTarget().getOwner(), access.getTarget().getName());
            if (reason != null) {
                add(events, lines, type, member, reason);
            }
        }
    }

    private static String callRefusal(JavaClass type, JavaClass owner, String name, ModuleOwnership ownership) {
        if (owner.isArray() || ALLOWED_TYPES.contains(owner.getName())) {
            return null;
        }
        if (denied(owner)) {
            return "calls " + owner.getName() + "." + name;
        }
        if ("java.lang.Class".equals(owner.getName()) && reflective(name)) {
            return "calls java.lang.Class." + name;
        }
        String iface = refusedInterface(type, owner, ownership);
        if (iface != null) {
            return "calls non-sealed API interface " + iface;
        }
        return null;
    }

    /** A constructor is value code when the constructed class is on the value denylist. */
    private static String constructRefusal(JavaClass owner) {
        if (owner.isArray() || ALLOWED_TYPES.contains(owner.getName())) {
            return null;
        }
        if (denied(owner)) {
            return "constructs " + owner.getName();
        }
        return null;
    }

    private static String fieldRefusal(JavaClass owner, String name) {
        if (owner.isArray() || ALLOWED_TYPES.contains(owner.getName())) {
            return null;
        }
        if (denied(owner)) {
            return "accesses " + owner.getName() + "." + name;
        }
        return null;
    }

    private static boolean denied(JavaClass owner) {
        if (DENIED_CLASSES.contains(owner.getName())) {
            return true;
        }
        for (String pkg : DENIED_PACKAGES) {
            if (ApiQualityRules.under(owner.getPackageName(), pkg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reflective(String name) {
        if (name.equals("forName") || name.equals("newInstance") || name.equals("getClassLoader")) {
            return true;
        }
        if (name.startsWith("getResource")) {
            return true;
        }
        return name.startsWith("get")
                && (name.contains("Method") || name.contains("Field") || name.contains("Constructor"));
    }

    /**
     * @return the refused interface's name, or null when the call is allowed
     */
    private static String refusedInterface(JavaClass type, JavaClass owner, ModuleOwnership ownership) {
        if (!owner.isInterface()) {
            return null;
        }
        ModuleOwnership.Owned owned = ownership.classify(owner);
        if (!owned.is(ModuleOwnership.Role.API) || sealed(owner)) {
            return null;
        }
        if (type.getName().equals(owner.getName())) {
            return null;
        }
        for (JavaClass iface : type.getAllRawInterfaces()) {
            if (iface.getName().equals(owner.getName())) {
                return null;
            }
        }
        return owner.getName();
    }

    private static boolean sealed(JavaClass type) {
        Class<?> reflected = ApiQualityRules.reflect(type);
        return reflected != null && reflected.isSealed();
    }

    private static boolean factoryReturn(JavaClass iface, JavaMethod method) {
        JavaClass returned = method.getRawReturnType();
        if (iface.getName().equals(returned.getName())) {
            return true;
        }
        Class<?> reflected = ApiQualityRules.reflect(iface);
        if (reflected == null) {
            return false;
        }
        Class<?>[] permitted = reflected.getPermittedSubclasses();
        if (permitted == null) {
            return false;
        }
        for (Class<?> candidate : permitted) {
            if (candidate.getName().equals(returned.getName())) {
                return true;
            }
        }
        return false;
    }

    /** A static initializer constructs a mutable collection when this constructor target says so. */
    private static boolean mutableConstruction(JavaClass constructed) {
        if (constructed.isArray() || "java.util.concurrent.TimeUnit".equals(constructed.getName())) {
            return false;
        }
        if (ApiQualityRules.under(constructed.getPackageName(), "java.util.concurrent")) {
            return true;
        }
        return MUTABLE_COLLECTIONS.contains(constructed.getName());
    }

    private static List<JavaClass> constructions(JavaCodeUnit body) {
        List<JavaClass> found = new ArrayList<>();
        for (JavaConstructorCall call : body.getConstructorCallsFromSelf()) {
            found.add(call.getTarget().getOwner());
        }
        for (JavaConstructorReference reference : body.getConstructorReferencesFromSelf()) {
            found.add(reference.getTarget().getOwner());
        }
        return found;
    }

    private static void sameClassCallees(JavaClass type, JavaCodeUnit body, Set<JavaCodeUnit> callees) {
        for (JavaMethodCall call : body.getMethodCallsFromSelf()) {
            if (type.getName().equals(call.getTarget().getOwner().getName())) {
                call.getTarget().resolveMember().ifPresent(member -> addCallee(member, callees));
            }
        }
        for (JavaConstructorCall call : body.getConstructorCallsFromSelf()) {
            if (type.getName().equals(call.getTarget().getOwner().getName())) {
                call.getTarget().resolveMember().ifPresent(member -> addCallee(member, callees));
            }
        }
    }

    private static void addCallee(JavaMember member, Set<JavaCodeUnit> callees) {
        if (member instanceof JavaCodeUnit unit && !unit.getName().startsWith("lambda$")
                && !(unit instanceof JavaStaticInitializer) && !generated(unit)) {
            callees.add(unit);
        }
    }

    private static List<JavaCodeUnit> roots(JavaClass type) {
        List<JavaCodeUnit> units = new ArrayList<>();
        for (JavaConstructor constructor : type.getConstructors()) {
            if (!generated(constructor)) {
                units.add(constructor);
            }
        }
        for (JavaMethod method : type.getMethods()) {
            if (!skipMethod(type, method)) {
                units.add(method);
            }
        }
        type.getStaticInitializer().ifPresent(units::add);
        return units;
    }

    /**
     * The member, the lambdas it holds, and the bodies of the anonymous classes it holds. An
     * anonymous class inside a lambda stays with that lambda's member. The reported member is the
     * one passed in, so a line never names the anonymous class.
     */
    private static List<JavaCodeUnit> withBodies(JavaClass type, JavaCodeUnit unit) {
        List<JavaCodeUnit> bodies = withLambdas(type, unit);
        for (JavaClass anonymous : ApiQualityRules.anonymousClasses(type)) {
            if (!heldBy(type, anonymous, unit)) {
                continue;
            }
            for (JavaCodeUnit code : anonymous.getCodeUnits()) {
                if (!generated(code)) {
                    bodies.add(code);
                }
            }
        }
        return bodies;
    }

    /**
     * The outermost anonymous class is declared in {@code unit}, or in a lambda {@code unit} holds.
     * ArchUnit leaves the enclosing code unit empty for an anonymous class written in a field or
     * static initializer, so those are found by the constructor call that creates the class.
     */
    private static boolean heldBy(JavaClass type, JavaClass anonymous, JavaCodeUnit unit) {
        JavaCodeUnit member = holdingMember(type, anonymous);
        return member != null && unit.getFullName().equals(member.getFullName())
                && unit.getOwner().getName().equals(member.getOwner().getName());
    }

    private static JavaCodeUnit holdingMember(JavaClass type, JavaClass anonymous) {
        JavaClass outer = anonymous;
        while (outer.getEnclosingClass().isPresent() && outer.getEnclosingClass().get().isAnonymousClass()) {
            outer = outer.getEnclosingClass().get();
        }
        JavaCodeUnit enclosing = outer.getEnclosingCodeUnit().orElse(null);
        if (enclosing == null) {
            enclosing = constructingUnit(type, outer);
        }
        if (enclosing == null) {
            return null;
        }
        return enclosing.getName().startsWith("lambda$") ? holder(type, enclosing) : enclosing;
    }

    private static JavaCodeUnit constructingUnit(JavaClass type, JavaClass anonymous) {
        for (JavaCodeUnit unit : type.getCodeUnits()) {
            for (JavaConstructorCall call : unit.getConstructorCallsFromSelf()) {
                if (anonymous.getName().equals(call.getTarget().getOwner().getName())) {
                    return unit;
                }
            }
        }
        return null;
    }

    /** The member plus the lambdas whose holding member it is. Nested lambdas stay with that member. */
    private static List<JavaCodeUnit> withLambdas(JavaClass type, JavaCodeUnit unit) {
        List<JavaCodeUnit> bodies = new ArrayList<>();
        bodies.add(unit);
        for (JavaMethod method : type.getMethods()) {
            if (method.getName().startsWith("lambda$") && unit.equals(holder(type, method))) {
                bodies.add(method);
            }
        }
        return bodies;
    }

    private static JavaCodeUnit holder(JavaClass type, JavaCodeUnit unit) {
        JavaCodeUnit current = unit;
        Set<String> seen = new LinkedHashSet<>();
        while (current.getName().startsWith("lambda$") && seen.add(current.getFullName())) {
            JavaCodeUnit parent = referencedFrom(type, current);
            if (parent == null) {
                parent = namedHolder(type, current.getName());
            }
            if (parent == null || parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return current;
    }

    private static JavaCodeUnit referencedFrom(JavaClass type, JavaCodeUnit lambda) {
        for (JavaCodeUnit unit : type.getCodeUnits()) {
            if (unit.equals(lambda)) {
                continue;
            }
            for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                if (sameMember(call.getTarget().getOwner(), call.getTarget().getName(), call.getTarget().getFullName(),
                        lambda)) {
                    return unit;
                }
            }
            for (JavaMethodReference reference : unit.getMethodReferencesFromSelf()) {
                if (sameMember(reference.getTarget().getOwner(), reference.getTarget().getName(),
                        reference.getTarget().getFullName(), lambda)) {
                    return unit;
                }
            }
        }
        return null;
    }

    private static boolean sameMember(JavaClass owner, String name, String fullName, JavaCodeUnit lambda) {
        return lambda.getName().equals(name) && lambda.getOwner().getName().equals(owner.getName())
                || lambda.getFullName().equals(fullName);
    }

    /** Javac names a lambda {@code lambda$<holding-member>$N}, and a nested one keeps that prefix. */
    private static JavaCodeUnit namedHolder(JavaClass type, String lambdaName) {
        if (!lambdaName.startsWith("lambda$")) {
            return null;
        }
        String rest = lambdaName.substring("lambda$".length());
        int dollar = rest.lastIndexOf('$');
        if (dollar <= 0) {
            return null;
        }
        String parent = rest.substring(0, dollar);
        if ("static".equals(parent)) {
            return type.getStaticInitializer().orElse(null);
        }
        if ("new".equals(parent)) {
            return type.getConstructors().size() == 1 ? type.getConstructors().iterator().next() : null;
        }
        if (parent.startsWith("lambda$")) {
            for (JavaMethod method : type.getMethods()) {
                if (method.getName().equals(parent)) {
                    return method;
                }
            }
            return null;
        }
        JavaCodeUnit found = null;
        for (JavaMethod method : type.getMethods()) {
            if (method.getName().equals(parent)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static boolean skipMethod(JavaClass type, JavaMethod method) {
        return generated(method) || method.getName().startsWith("lambda$") || enumGenerated(type, method);
    }

    private static boolean generated(JavaMember member) {
        Set<JavaModifier> modifiers = member.getModifiers();
        if (modifiers.contains(JavaModifier.SYNTHETIC) || modifiers.contains(JavaModifier.BRIDGE)) {
            return true;
        }
        String name = member.getName();
        return name.contains("$VALUES") || name.contains("$SwitchMap");
    }

    /** {@code values()} and {@code valueOf(String)} are compiler-generated and not marked synthetic. */
    private static boolean enumGenerated(JavaClass type, JavaMethod method) {
        if (!type.isEnum() || !method.getModifiers().contains(JavaModifier.STATIC)) {
            return false;
        }
        if ("values".equals(method.getName()) && method.getRawParameterTypes().isEmpty()) {
            JavaClass returned = method.getRawReturnType();
            return returned.isArray() && type.getName().equals(returned.getComponentType().getName());
        }
        if ("valueOf".equals(method.getName()) && method.getRawParameterTypes().size() == 1
                && "java.lang.String".equals(method.getRawParameterTypes().get(0).getName())) {
            return type.getName().equals(method.getRawReturnType().getName());
        }
        return false;
    }

    private static String member(JavaCodeUnit unit) {
        if (unit instanceof JavaStaticInitializer) {
            return "static initializer";
        }
        if (unit.isConstructor()) {
            List<String> parameters = new ArrayList<>();
            for (JavaClass parameter : unit.getRawParameterTypes()) {
                parameters.add(parameter.getName());
            }
            return "constructor(" + String.join(",", parameters) + ")";
        }
        return "method " + unit.getName();
    }

    private static void add(ConditionEvents events, Set<String> lines, JavaClass type, String member, String reason) {
        String line = type.getName() + " " + member + " " + reason;
        if (lines.add(line)) {
            events.add(SimpleConditionEvent.violated(type, line));
        }
    }
}
