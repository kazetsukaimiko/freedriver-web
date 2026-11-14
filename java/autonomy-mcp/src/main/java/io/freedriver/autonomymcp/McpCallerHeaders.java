package io.freedriver.autonomymcp;

import io.freedriver.mcp.CallerIds;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;


/**
 * The caller's subject and roles, and the internal secret, on every dashboard call. The incoming
 * Authorization header is never copied onto a dashboard route. The connecting-user child (#475)
 * replaces these headers.
 */
@ApplicationScoped
public class McpCallerHeaders implements ClientHeadersFactory {

    public static final String SUBJECT_HEADER = "X-Freedriver-Subject";
    public static final String ROLES_HEADER = "X-Freedriver-Roles";
    public static final String SECRET_HEADER = "X-Freedriver-Internal";

    private final SecurityIdentity identity;
    private final String secret;

    @Inject
    McpCallerHeaders(SecurityIdentity identity, AutonomyMcpConfig config) {
        this.identity = identity;
        this.secret = config.internalSecret().map(String::trim).orElse("");
    }

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incoming,
                                                 MultivaluedMap<String, String> outgoing) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        String subject = CallerIds.subject(identity);
        if (subject != null) {
            headers.putSingle(SUBJECT_HEADER, subject);
        }
        headers.putSingle(ROLES_HEADER, String.join(",", CallerIds.roles(identity)));
        headers.putSingle(SECRET_HEADER, secret);
        return headers;
    }
}
