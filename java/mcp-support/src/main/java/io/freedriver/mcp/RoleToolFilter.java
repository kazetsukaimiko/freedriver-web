package io.freedriver.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.McpMethod;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Vetoed;

import java.lang.reflect.Method;

/**
 * On {@code tools/list}, a tool is listed when {@code tool.method()} is present, that method
 * carries its own {@code @RolesAllowed}, and {@link SecurityIdentity#hasRole(String)} is true for
 * one of those role names. A missing method, a method with no {@code @RolesAllowed}, and a role
 * that exists only on the class stay hidden. Every other MCP method, including {@code tools/call},
 * passes, so the method annotation remains the authorization check.
 *
 * <p>The filter reads the identity of the current request. It does not notify {@code tools/list_changed}.
 *
 * <p>{@code @Vetoed} holds this class out of bean discovery. quarkus-mcp-server assigns
 * {@code @Singleton} to every {@code ToolFilter}, so an unscoped implementation is already a bean
 * in both servers. The build replaces {@code @Vetoed} with {@code @ApplicationScoped}.
 */
@Vetoed
public class RoleToolFilter implements ToolFilter {

    private final SecurityIdentity identity;

    public RoleToolFilter(SecurityIdentity identity) {
        this.identity = identity;
    }

    @Override
    public boolean test(ToolManager.ToolInfo tool, FilterContext context) {
        if (context.method() != McpMethod.TOOLS_LIST) {
            return true;
        }
        Method method = tool.method().orElse(null);
        if (method == null) {
            return false;
        }
        RolesAllowed allowed = method.getAnnotation(RolesAllowed.class);
        if (allowed == null) {
            return false;
        }
        for (String role : allowed.value()) {
            if (identity.hasRole(role)) {
                return true;
            }
        }
        return false;
    }
}
