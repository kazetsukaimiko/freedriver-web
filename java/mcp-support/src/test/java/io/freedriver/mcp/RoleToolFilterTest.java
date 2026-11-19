package io.freedriver.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.McpMethod;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleToolFilterTest {

    private static final String[] DISPATCHER_TOOLS = {
            "listAgents", "openbotSendPrompt", "openbotAwaitTurn", "getJob", "listJobs"
    };

    private static final String[] DEVICE_TOOLS = {"reportHost", "finishJob"};

    private static final String[] FORBIDDEN_SOURCE = {
            "LaunchMode",
            "ProfileManager",
            "ConfigUtils",
            "@IfBuildProfile",
            "@UnlessBuildProfile",
            "quarkus.oidc.enabled"
    };

    @Test
    void tools_list_hides_a_tool_with_no_method_no_annotation_or_only_a_class_annotation() throws Exception {
        RoleToolFilter filter = new RoleToolFilter(identity("dashboard"));
        assertFalse(filter.test(tool(null), context(McpMethod.TOOLS_LIST)));
        assertFalse(filter.test(tool(Samples.class.getDeclaredMethod("bare")), context(McpMethod.TOOLS_LIST)));
        assertFalse(filter.test(
                tool(ClassAnnotated.class.getDeclaredMethod("onlyOnTheClass")),
                context(McpMethod.TOOLS_LIST)));
    }

    @Test
    void tools_list_shows_a_tool_when_the_caller_holds_one_of_several_roles() throws Exception {
        Method shared = Samples.class.getDeclaredMethod("shared");
        assertTrue(new RoleToolFilter(identity("dashboard")).test(tool(shared), context(McpMethod.TOOLS_LIST)));
        assertTrue(new RoleToolFilter(identity("mcp-user")).test(tool(shared), context(McpMethod.TOOLS_LIST)));
        assertFalse(new RoleToolFilter(identity("mcp-debug")).test(tool(shared), context(McpMethod.TOOLS_LIST)));
    }

    @Test
    void dashboard_alone_lists_switch_appliances_and_mcp_debug_alone_lists_debug() throws Exception {
        RoleToolFilter dashboard = new RoleToolFilter(identity("dashboard"));
        assertTrue(dashboard.test(tool(Samples.class.getDeclaredMethod("switchAppliances")), context(McpMethod.TOOLS_LIST)));
        assertFalse(dashboard.test(tool(Samples.class.getDeclaredMethod("listAppliances")), context(McpMethod.TOOLS_LIST)));
        assertFalse(dashboard.test(tool(Samples.class.getDeclaredMethod("homeStatus")), context(McpMethod.TOOLS_LIST)));
        assertFalse(dashboard.test(tool(Samples.class.getDeclaredMethod("debug")), context(McpMethod.TOOLS_LIST)));

        RoleToolFilter debug = new RoleToolFilter(identity("mcp-debug"));
        assertTrue(debug.test(tool(Samples.class.getDeclaredMethod("debug")), context(McpMethod.TOOLS_LIST)));
        assertFalse(debug.test(tool(Samples.class.getDeclaredMethod("switchAppliances")), context(McpMethod.TOOLS_LIST)));
        assertFalse(debug.test(tool(Samples.class.getDeclaredMethod("listAppliances")), context(McpMethod.TOOLS_LIST)));
        assertFalse(debug.test(tool(Samples.class.getDeclaredMethod("homeStatus")), context(McpMethod.TOOLS_LIST)));
    }

    @Test
    void dispatcher_admin_lists_none_of_the_dispatcher_tools() throws Exception {
        RoleToolFilter admin = new RoleToolFilter(identity("dispatcher-admin"));
        for (String name : DISPATCHER_TOOLS) {
            assertFalse(admin.test(tool(Samples.class.getDeclaredMethod(name)), context(McpMethod.TOOLS_LIST)), name);
        }
        for (String name : DEVICE_TOOLS) {
            assertFalse(admin.test(tool(Samples.class.getDeclaredMethod(name)), context(McpMethod.TOOLS_LIST)), name);
        }
    }

    @Test
    void anonymous_and_no_role_identities_are_shown_no_tools() throws Exception {
        Method listed = Samples.class.getDeclaredMethod("listAppliances");
        FilterContext list = context(McpMethod.TOOLS_LIST);
        assertFalse(new RoleToolFilter(anonymous()).test(tool(listed), list));
        assertFalse(new RoleToolFilter(noRole()).test(tool(listed), list));
        assertFalse(new RoleToolFilter(anonymous()).test(tool(null), list));
        assertFalse(new RoleToolFilter(noRole()).test(tool(Samples.class.getDeclaredMethod("bare")), list));
    }

    @Test
    void tools_call_always_passes_the_filter() throws Exception {
        FilterContext call = context(McpMethod.TOOLS_CALL);
        RoleToolFilter anonymous = new RoleToolFilter(anonymous());
        assertTrue(anonymous.test(tool(null), call));
        assertTrue(anonymous.test(tool(Samples.class.getDeclaredMethod("bare")), call));
        assertTrue(anonymous.test(tool(ClassAnnotated.class.getDeclaredMethod("onlyOnTheClass")), call));
        assertTrue(anonymous.test(tool(Samples.class.getDeclaredMethod("switchAppliances")), call));
        assertTrue(new RoleToolFilter(identity("mcp-user")).test(
                tool(Samples.class.getDeclaredMethod("switchAppliances")), call));
    }

    @Test
    void source_names_no_profile_or_oidc_check() throws Exception {
        Path source = Path.of("src/main/java/io/freedriver/mcp/RoleToolFilter.java");
        assertTrue(Files.isRegularFile(source), "RoleToolFilter.java");
        String body = Files.readString(source);
        for (String token : FORBIDDEN_SOURCE) {
            assertFalse(body.contains(token), token);
        }
        assertTrue(body.contains("@Vetoed"),
                "@Vetoed holds the filter out of both servers until the build");
    }

    private static SecurityIdentity identity(String role) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("ada"))
                .addRole(role)
                .build();
    }

    private static SecurityIdentity noRole() {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("ada"))
                .build();
    }

    private static SecurityIdentity anonymous() {
        return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
    }

    private static ToolManager.ToolInfo tool(Method method) {
        return proxy(ToolManager.ToolInfo.class, (proxy, invoked, args) -> {
            if ("method".equals(invoked.getName()) && invoked.getParameterCount() == 0) {
                return Optional.ofNullable(method);
            }
            return null;
        });
    }

    private static FilterContext context(McpMethod method) {
        return proxy(FilterContext.class, (proxy, invoked, args) -> {
            if ("method".equals(invoked.getName()) && invoked.getParameterCount() == 0) {
                return method;
            }
            return null;
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (target, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    default -> null;
                };
            }
            Object value = handler.invoke(target, method, args);
            if (value != null || !method.getReturnType().isPrimitive()) {
                return value;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == int.class) {
                return 0;
            }
            return null;
        });
        return type.cast(proxy);
    }

    @RolesAllowed("dashboard")
    static final class ClassAnnotated {
        void onlyOnTheClass() {
        }
    }

    static final class Samples {
        @RolesAllowed("dashboard")
        void switchAppliances() {
        }

        @RolesAllowed("mcp-user")
        void listAppliances() {
        }

        @RolesAllowed("mcp-user")
        void homeStatus() {
        }

        @RolesAllowed("mcp-debug")
        void debug() {
        }

        @RolesAllowed({"mcp-user", "dashboard"})
        void shared() {
        }

        @RolesAllowed("dispatcher")
        void listAgents() {
        }

        @RolesAllowed("dispatcher")
        void openbotSendPrompt() {
        }

        @RolesAllowed("dispatcher")
        void openbotAwaitTurn() {
        }

        @RolesAllowed("dispatcher")
        void getJob() {
        }

        @RolesAllowed("dispatcher")
        void listJobs() {
        }

        @RolesAllowed("device")
        void reportHost() {
        }

        @RolesAllowed("device")
        void finishJob() {
        }

        void bare() {
        }
    }
}
