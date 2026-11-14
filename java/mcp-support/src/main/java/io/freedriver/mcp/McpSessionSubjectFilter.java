package io.freedriver.mcp;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The MCP server issues {@code Mcp-Session-Id}. This filter binds that id to the caller's subject
 * and refuses an id bound to someone else before the tool runs. When {@code freedriver.mcp.roles}
 * is set, a caller with none of those roles is refused on the MCP paths.
 */
@ApplicationScoped
public class McpSessionSubjectFilter {

    private final McpSessions sessions;
    private final McpEndpoint endpoint;

    @Inject
    public McpSessionSubjectFilter(McpSessions sessions, McpEndpoint endpoint) {
        this.sessions = sessions;
        this.endpoint = endpoint;
    }

    /**
     * HTTP authentication is priority 200 and the permission check is 100.
     * 150 runs after the bearer identity is set and before that check.
     */
    void register(@Observes Filters filters) {
        filters.register(this::bind, 150);
    }

    void bind(RoutingContext context) {
        if (!endpoint.isMcpPath(context.normalizedPath()) && !endpoint.isMcpPath(context.request().path())) {
            context.next();
            return;
        }
        SecurityIdentity identity = identity(context);
        if (identity == null || identity.isAnonymous()) {
            context.next();
            return;
        }
        if (!endpoint.roles().isEmpty() && endpoint.roles().stream().noneMatch(identity::hasRole)) {
            context.response().setStatusCode(403).end();
            return;
        }
        String subject = CallerIds.subject(identity);
        String sessionId = context.request().getHeader(McpEndpoint.SESSION_HEADER);
        // The Redis read does not block the event loop; the request waits paused until it answers.
        context.request().pause();
        sessions.check(sessionId, subject).subscribe().with(
                status -> {
                    context.request().resume();
                    proceed(context, status, sessionId, subject);
                },
                failure -> {
                    context.request().resume();
                    context.fail(503, failure);
                });
    }

    private void proceed(RoutingContext context, McpSessions.Status status, String sessionId, String subject) {
        if (status == McpSessions.Status.OTHER_SUBJECT) {
            context.response().setStatusCode(403).end();
            return;
        }
        context.addBodyEndHandler(ignored -> {
            String issued = context.response().headers().get(McpEndpoint.SESSION_HEADER);
            if (issued != null && !issued.isBlank()) {
                sessions.remember(issued, subject).subscribe().with(done -> { }, failure -> { });
            }
            if (status == McpSessions.Status.MATCH && "DELETE".equals(context.request().method().name())) {
                sessions.close(sessionId, subject).subscribe().with(done -> { }, failure -> { });
            }
        });
        context.next();
    }

    private static SecurityIdentity identity(RoutingContext context) {
        if (context.user() instanceof QuarkusHttpUser user) {
            return user.getSecurityIdentity();
        }
        return null;
    }
}
