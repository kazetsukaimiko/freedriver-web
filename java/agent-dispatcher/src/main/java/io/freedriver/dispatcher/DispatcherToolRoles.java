package io.freedriver.dispatcher;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.McpMethod;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/**
 * A dispatcher caller sees the send tools. A device caller sees the host tools.
 * A call still reaches the tool method, which answers a wrong role itself.
 */
@ApplicationScoped
public class DispatcherToolRoles implements ToolFilter {

    private static final Set<String> DEVICE = Set.of("report_host", "finish_job");
    private static final Set<String> DISPATCHER = Set.of(
            "list_agents", "openbot_send_prompt", "openbot_await_turn", "get_job");

    private final SecurityIdentity identity;

    @Inject
    public DispatcherToolRoles(SecurityIdentity identity) {
        this.identity = identity;
    }

    @Override
    public boolean test(ToolManager.ToolInfo tool, FilterContext context) {
        if (context.method() == McpMethod.TOOLS_CALL) {
            return true;
        }
        boolean device = identity.hasRole("device");
        boolean dispatcher = identity.hasRole("dispatcher");
        if (DEVICE.contains(tool.name())) {
            return device;
        }
        if (DISPATCHER.contains(tool.name())) {
            return dispatcher || !device;
        }
        return false;
    }
}
