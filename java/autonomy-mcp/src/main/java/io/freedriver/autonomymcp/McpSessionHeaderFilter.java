package io.freedriver.autonomymcp;

import io.freedriver.autonomymcp.api.McpApi;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
public class McpSessionHeaderFilter implements ContainerResponseFilter {

    private final McpSessionHeader session;

    @Inject
    public McpSessionHeaderFilter(McpSessionHeader session) {
        this.session = session;
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (!McpProtectedResource.isMcpPath(request.getUriInfo().getPath())) {
            return;
        }
        String sessionId = session.get();
        if (sessionId != null) {
            response.getHeaders().putSingle(McpApi.SESSION_HEADER, sessionId);
        }
        response.getHeaders().remove("Set-Cookie");
        response.getHeaders().remove("Location");
    }
}
