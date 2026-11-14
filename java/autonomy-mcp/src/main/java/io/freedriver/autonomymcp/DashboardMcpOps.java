package io.freedriver.autonomymcp;

import io.freedriver.dashboard.api.McpOpsApi;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The dashboard's MCP reads and scheduled switches over the compose network, as the MCP caller. */
@RegisterRestClient(configKey = "dashboard")
@RegisterClientHeaders(McpCallerHeaders.class)
@RegisterProvider(DashboardResponses.class)
public interface DashboardMcpOps extends McpOpsApi {
}
