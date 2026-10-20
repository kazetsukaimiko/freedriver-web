package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.McpApi;
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
        String path = request.getUriInfo().getPath();
        if (!"mcp".equals(path) && !"/mcp".equals(path)) {
            return;
        }
        String sessionId = session.get();
        if (sessionId != null) {
            response.getHeaders().putSingle(McpApi.SESSION_HEADER, sessionId);
        }
    }
}
