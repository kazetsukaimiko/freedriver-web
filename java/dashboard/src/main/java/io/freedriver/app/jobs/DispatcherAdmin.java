package io.freedriver.app.jobs;

import io.freedriver.dispatcher.api.DispatcherAdminApi;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The dispatcher, called as the portal's service account ({@code freedriver-portal-jobs}, realm role
 * {@code dispatcher-admin}). The {@code portal-jobs} OIDC client supplies the token. It reaches only the
 * admin list: the contract carries no role, so the client checks nothing about the signed-in user.
 */
@RegisterRestClient(configKey = "dispatcher")
@OidcClientFilter("portal-jobs")
@RegisterProvider(DispatcherResponses.class)
public interface DispatcherAdmin extends DispatcherAdminApi {
}
