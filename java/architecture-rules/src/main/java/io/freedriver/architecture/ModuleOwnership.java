package io.freedriver.architecture;

import com.tngtech.archunit.core.domain.JavaClass;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which reactor module a class was compiled in, and the role that class plays. The module is the
 * one whose groupId is {@code io.freedriver} (sms publishes under {@code io.freedriver.sms}) and
 * whose output holds the class. A jar that is not one of those modules is third-party, whatever its
 * file name. A class with no resolvable location stays unresolved.
 *
 * <p>The role comes from the class's package: {@code io.freedriver.<project>[.<area>]} followed by
 * {@code api}, {@code impl.<name>} or {@code service}, and every package below that segment. A class
 * whose package matches none of these takes its module's artifact role until the package renames.
 */
final class ModuleOwnership {

    /** Utility artifactIds named by the API quality gate for this repository. */
    static final List<String> UTILITY_ARTIFACTS = List.of(
            "stereotypes", "mcp-support", "hit-window", "freedriver-mqtt");

    enum Role {
        API, IMPL, UTILITY, SERVICE
    }

    enum Kind {
        OURS, THIRD_PARTY, UNRESOLVED
    }

    /**
     * One resolved owner. {@code kind}, {@code groupId} and {@code artifactId} identify the module.
     * {@code packageRole} and {@code effectiveRole} describe the class. {@code detail} is the
     * artifactId, the jar file name, or why it stayed unresolved.
     *
     * @param packageRole the role the class's package names, empty when it names none
     * @param effectiveRole the package role, else the module's artifact role; null off our modules
     */
    record Owned(Kind kind, String groupId, String artifactId, String detail,
                 Optional<Role> packageRole, Role effectiveRole) {

        /** A module of ours, before a class's package is known: the artifact role stands. */
        static Owned ours(String groupId, String artifactId) {
            return new Owned(Kind.OURS, groupId, artifactId, artifactId, Optional.empty(), roleOf(artifactId));
        }

        static Owned thirdParty(String detail) {
            return new Owned(Kind.THIRD_PARTY, "", "", detail, Optional.empty(), null);
        }

        static Owned unresolved(String detail) {
            return new Owned(Kind.UNRESOLVED, "", "", detail, Optional.empty(), null);
        }

        /** This module's owner for a class in {@code pkg}. */
        Owned inPackage(String pkg) {
            if (kind != Kind.OURS) {
                return this;
            }
            Optional<Role> named = ModuleOwnership.packageRole(pkg);
            return new Owned(kind, groupId, artifactId, detail, named, named.orElseGet(() -> roleOf(artifactId)));
        }

        boolean ours() {
            return kind == Kind.OURS;
        }

        boolean is(Role role) {
            return kind == Kind.OURS && effectiveRole == role;
        }

        boolean sameModule(Owned other) {
            return kind == Kind.OURS && other.kind == Kind.OURS
                    && groupId.equals(other.groupId) && artifactId.equals(other.artifactId);
        }
    }

    private record Module(Path dir, String groupId, String artifactId, String version) {
        Owned owned() {
            return Owned.ours(groupId, artifactId);
        }

        String jarFileName() {
            return artifactId + "-" + version + ".jar";
        }
    }

    private static final ModuleOwnership REACTOR = load();

    private final Map<Path, Module> byDir;
    private final List<Module> modules;
    private final Map<String, Owned> exact;
    private final Map<String, Owned> cache = new ConcurrentHashMap<>();

    private ModuleOwnership(Map<Path, Module> byDir, List<Module> modules, Map<String, Owned> exact) {
        this.byDir = byDir;
        this.modules = modules;
        this.exact = exact;
    }

    static ModuleOwnership reactor() {
        return REACTOR;
    }

    /** Class-name owners supplied by a fixture. Everything else still resolves from its location. */
    ModuleOwnership overlay(Map<String, Owned> extra) {
        Map<String, Owned> merged = new LinkedHashMap<>(exact);
        merged.putAll(extra);
        return new ModuleOwnership(byDir, modules, Map.copyOf(merged));
    }

    boolean overlaid(JavaClass type) {
        return exact.containsKey(type.getName());
    }

    /** The class's module, with the role its package gives it. A fixture's owner names the module only. */
    Owned classify(JavaClass type) {
        Owned over = exact.get(type.getName());
        if (over != null) {
            return over.inPackage(type.getPackageName());
        }
        return cache.computeIfAbsent(type.getName(), name -> type.getSource()
                .map(source -> classifyUri(source.getUri()))
                .orElseGet(() -> Owned.unresolved(name)))
                .inPackage(type.getPackageName());
    }

    /** The module that owns a location. The class role is added by {@link Owned#inPackage(String)}. */
    Owned classifyUri(URI uri) {
        if (uri == null) {
            return Owned.unresolved("no location");
        }
        if ("jar".equals(uri.getScheme())) {
            String ssp = uri.getSchemeSpecificPart();
            int bang = ssp.indexOf('!');
            String filePart = bang < 0 ? ssp : ssp.substring(0, bang);
            return classifyJar(Path.of(URI.create(filePart)));
        }
        if ("file".equals(uri.getScheme())) {
            return classifyFile(Path.of(uri));
        }
        return Owned.unresolved(uri.toString());
    }

    private Owned classifyFile(Path path) {
        int names = path.getNameCount();
        for (int i = 0; i < names - 1; i++) {
            String name = path.getName(i).toString();
            String next = path.getName(i + 1).toString();
            if ("target".equals(name) && ("classes".equals(next) || "test-classes".equals(next))) {
                Path moduleDir = path.getRoot() == null
                        ? path.subpath(0, i)
                        : path.getRoot().resolve(path.subpath(0, i).toString());
                return moduleAt(moduleDir.normalize()).map(Module::owned)
                        .orElseGet(() -> Owned.unresolved(path.toString()));
            }
        }
        return Owned.unresolved(path.toString());
    }

    private Owned classifyJar(Path jar) {
        Path parent = jar.getParent();
        if (parent != null && "target".equals(String.valueOf(parent.getFileName()))) {
            Optional<Module> module = moduleAt(parent.getParent());
            if (module.isPresent() && module.get().jarFileName().equals(String.valueOf(jar.getFileName()))) {
                return module.get().owned();
            }
        }
        String fileName = String.valueOf(jar.getFileName());
        String path = jar.toString().replace('\\', '/');
        if (path.contains("/io/freedriver/")) {
            for (Module module : modules) {
                if (fileName.equals(module.jarFileName())) {
                    return module.owned();
                }
            }
        }
        return Owned.thirdParty(fileName);
    }

    private Optional<Module> moduleAt(Path dir) {
        if (dir == null) {
            return Optional.empty();
        }
        Module known = byDir.get(dir.normalize());
        if (known != null) {
            return Optional.of(known);
        }
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return Optional.empty();
        }
        Module read = readModule(pom);
        if (read == null || !ours(read.groupId)) {
            return Optional.empty();
        }
        return Optional.of(read);
    }

    /**
     * The role a package names: {@code io.freedriver.<project>} or
     * {@code io.freedriver.<project>.<area>}, then {@code api}, {@code service} or {@code impl.<name>}.
     * Packages below the role segment take the same role. {@code impl} with no name segment, a second
     * area segment, and a package with no project segment name no role.
     */
    static Optional<Role> packageRole(String pkg) {
        int at = roleSegment(pkg);
        if (at < 0) {
            return Optional.empty();
        }
        return Optional.of(switch (pkg.split("\\.")[at]) {
            case "api" -> Role.API;
            case "service" -> Role.SERVICE;
            default -> Role.IMPL;
        });
    }

    /** The {@code <name>} of an implementation package, so {@code io.freedriver.x.impl.mongo.codec} gives mongo. */
    static Optional<String> implementationName(String pkg) {
        int at = roleSegment(pkg);
        String[] segments = pkg.split("\\.");
        return at >= 0 && "impl".equals(segments[at]) ? Optional.of(segments[at + 1]) : Optional.empty();
    }

    /** Index of the role segment, or -1. The project is segment 2, so the role is segment 3 or 4. */
    private static int roleSegment(String pkg) {
        String[] segments = pkg.split("\\.");
        if (segments.length < 4 || !"io".equals(segments[0]) || !"freedriver".equals(segments[1])) {
            return -1;
        }
        for (int at = 3; at <= 4 && at < segments.length; at++) {
            switch (segments[at]) {
                case "api", "service" -> {
                    return at;
                }
                case "impl" -> {
                    return at + 1 < segments.length ? at : -1;
                }
                default -> {
                    // An area segment: the role may follow it.
                }
            }
        }
        return -1;
    }

    /** The artifact role: what a class takes when its package names none. */
    static Role roleOf(String artifactId) {
        if (artifactId.endsWith("-api") || artifactId.endsWith("-model") || artifactId.endsWith("-contract")) {
            return Role.API;
        }
        if (artifactId.endsWith("-impl")) {
            return Role.IMPL;
        }
        if (UTILITY_ARTIFACTS.contains(artifactId)) {
            return Role.UTILITY;
        }
        return Role.SERVICE;
    }

    /** {@code io.freedriver} and the sms group {@code io.freedriver.sms}. */
    static boolean ours(String groupId) {
        return "io.freedriver".equals(groupId) || (groupId != null && groupId.startsWith("io.freedriver."));
    }

    private static ModuleOwnership load() {
        Map<Path, Module> byDir = new LinkedHashMap<>();
        List<Module> modules = new ArrayList<>();
        Path root = findRepoRoot();
        if (root != null) {
            walk(root.resolve("pom.xml"), byDir, modules);
        }
        modules.sort(Comparator.comparingInt((Module m) -> m.artifactId.length()).reversed());
        return new ModuleOwnership(Map.copyOf(byDir), List.copyOf(modules), Map.of());
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String text = Files.readString(pom);
                    if (text.contains("<artifactId>freedriver-web</artifactId>")
                            && text.contains("<module>mqtt-contract</module>")) {
                        return dir;
                    }
                } catch (IOException ignored) {
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static void walk(Path pom, Map<Path, Module> byDir, List<Module> modules) {
        if (!Files.isRegularFile(pom)) {
            return;
        }
        Element project = parse(pom);
        Module module = readModule(project, pom);
        if (module != null && ours(module.groupId)) {
            byDir.put(module.dir.normalize(), module);
            modules.add(module);
        }
        Element modulesElement = child(project, "modules");
        if (modulesElement == null) {
            return;
        }
        for (Element listed : children(modulesElement, "module")) {
            walk(pom.getParent().resolve(text(listed)).resolve("pom.xml").normalize(), byDir, modules);
        }
    }

    private static Module readModule(Path pom) {
        return readModule(parse(pom), pom);
    }

    private static Module readModule(Element project, Path pom) {
        String artifactId = childText(project, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            return null;
        }
        String groupId = childText(project, "groupId");
        Element parent = child(project, "parent");
        if (groupId == null && parent != null) {
            groupId = childText(parent, "groupId");
        }
        String version = childText(project, "version");
        if ((version == null || version.startsWith("${")) && parent != null) {
            String parentVersion = childText(parent, "version");
            if (parentVersion != null && !parentVersion.startsWith("${")) {
                version = parentVersion;
            } else {
                String relative = childText(parent, "relativePath");
                Path parentPom = pom.getParent().resolve(relative == null ? "../pom.xml" : relative).normalize();
                if (Files.isRegularFile(parentPom)) {
                    version = childText(parse(parentPom), "version");
                    if (version == null) {
                        Element parentParent = child(parse(parentPom), "parent");
                        version = parentParent == null ? null : childText(parentParent, "version");
                    }
                }
            }
        }
        if (version == null || version.startsWith("${")) {
            version = "1.0.0-SNAPSHOT";
        }
        return new Module(pom.getParent().normalize(), groupId == null ? "" : groupId, artifactId, version);
    }

    private static Element parse(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + pom, e);
        }
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(localName(element))) {
                found.add(element);
            }
        }
        return found;
    }

    private static String childText(Element parent, String name) {
        Element element = child(parent, name);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null ? null : value.trim();
    }

    private static String text(Element element) {
        String value = element.getTextContent();
        return value == null ? "" : value.trim();
    }

    private static String localName(Node node) {
        return node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
    }
}
