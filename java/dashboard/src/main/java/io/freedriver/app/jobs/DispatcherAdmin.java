package io.freedriver.app.jobs;

import io.freedriver.dispatcher.api.DispatcherApi;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The dispatcher, called as the portal's service account ({@code freedriver-portal-jobs}, realm role
 * {@code dispatcher-admin}). The {@code portal-jobs} OIDC client supplies the token.
 */
@RegisterRestClient(configKey = "dispatcher")
@OidcClientFilter("portal-jobs")
@RegisterProvider(DispatcherResponses.class)
public interface DispatcherAdmin extends DispatcherApi {
}
