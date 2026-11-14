package io.freedriver.autonomymcp;

import io.freedriver.dashboard.api.AppliancesApi;
import io.quarkus.oidc.token.propagation.common.AccessToken;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The dashboard's appliance commands over the compose network, as the connecting user: the caller's
 * access token, exchanged at Keycloak for one whose audience is the dashboard.
 */
@RegisterRestClient(configKey = "dashboard")
@AccessToken
@RegisterProvider(DashboardResponses.class)
public interface DashboardAppliances extends AppliancesApi {
}
