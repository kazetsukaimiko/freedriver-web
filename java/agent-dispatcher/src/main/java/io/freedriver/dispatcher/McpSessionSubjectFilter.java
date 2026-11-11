package io.freedriver.dispatcher;

import io.freedriver.dispatcher.api.McpApi;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The MCP server issues {@code MCP-Session-Id}. This filter binds that id to the
 * bearer subject and rejects an id bound to someone else before the tool runs.
 */
@ApplicationScoped
public class McpSessionSubjectFilter {

    private final McpSessions sessions;

    @Inject
    public McpSessionSubjectFilter(McpSessions sessions) {
        this.sessions = sessions;
    }

    /**
     * HTTP authentication is priority 200 and the permission check is 100.
     * 150 runs after the bearer identity is set and before that check.
     */
    void register(@Observes Filters filters) {
        filters.register(this::bind, 150);
    }

    void bind(RoutingContext context) {
        if (!mcp(context)) {
            context.next();
            return;
        }
        SecurityIdentity identity = identity(context);
        if (identity == null || identity.isAnonymous()) {
            context.next();
            return;
        }
        if (!identity.hasRole("dispatcher") && !identity.hasRole("device")) {
            context.response().setStatusCode(403).end();
            return;
        }
        String subject = identity.getPrincipal().getName();
        String sessionId = context.request().getHeader(McpApi.SESSION_HEADER);
        McpSessions.Status status = sessions.check(sessionId, subject);
        if (status == McpSessions.Status.OTHER_SUBJECT) {
            context.response().setStatusCode(403).end();
            return;
        }
        context.addBodyEndHandler(ignored -> {
            String issued = context.response().headers().get(McpApi.SESSION_HEADER);
            if (issued != null && !issued.isBlank()) {
                sessions.remember(issued, subject);
            }
            if (status == McpSessions.Status.MATCH && context.request().method().name().equals("DELETE")) {
                sessions.close(sessionId, subject);
            }
        });
        context.next();
    }

    private static boolean mcp(RoutingContext context) {
        return McpProtectedResource.isMcpPath(context.normalizedPath())
                || McpProtectedResource.isMcpPath(context.request().path());
    }

    private static SecurityIdentity identity(RoutingContext context) {
        if (context.user() instanceof QuarkusHttpUser user) {
            return user.getSecurityIdentity();
        }
        return null;
    }
}
