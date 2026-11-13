package io.freedriver.autonomymcp;

import io.freedriver.dashboard.api.AppliancesApi;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The dashboard's appliance commands over the compose network, as the MCP caller. */
@RegisterRestClient(configKey = "dashboard")
@RegisterClientHeaders(McpCallerHeaders.class)
@RegisterProvider(DashboardResponses.class)
public interface DashboardAppliances extends AppliancesApi {
}
