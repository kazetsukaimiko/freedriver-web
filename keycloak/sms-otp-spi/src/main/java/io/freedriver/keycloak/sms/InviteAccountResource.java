package io.freedriver.keycloak.sms;

import jakarta.enterprise.context.Dependent;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import java.security.SecureRandom;

/**
 * Realm resource for the invite service account. It creates a phone user and grants only the
 * realm role {@code dashboard}. The path is {@code /realms/{realm}/freedriver-invite/users}.
 * Keycloak 24+ RESTEasy Reactive 405s when this class is {@code @Provider}. CDI Lite treats
 * beans.xml {@code all} as {@code annotated}, so {@code @Dependent} is the bean-defining
 * annotation that puts {@code POST /users} in the resource index. The SPI still constructs
 * this with a session; the no-arg constructor is for Arc.
 */
@Dependent
public class InviteAccountResource {

    static final String CLIENT_ID = "freedriver-invite";

    private final KeycloakSession session;
    private final SecureRandom random = new SecureRandom();

    public InviteAccountResource() {
        this.session = null;
    }

    public InviteAccountResource(KeycloakSession session) {
        this.session = session;
    }

    @POST
    @Path("users")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateUser body) {
        if (!inviteServiceAccount()) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("{}").build();
        }
        String phone = body == null ? null : body.phone();
        try {
            InviteAccounts.Created created = InviteAccounts.create(session, session.getContext().getRealm(), phone, random);
            return Response.ok(created).build();
        } catch (InviteAccountException e) {
            int status = e.status() == 400 ? 400 : e.status() == 409 ? 409 : 503;
            return Response.status(status).entity("{}").build();
        }
    }

    /** True only for the {@code freedriver-invite} service account. A user token is refused. */
    private boolean inviteServiceAccount() {
        if (session == null) {
            return false;
        }
        AuthenticationManager.AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        if (auth == null || auth.getUser() == null) {
            return false;
        }
        RealmModel realm = session.getContext().getRealm();
        ClientModel client = realm.getClientByClientId(CLIENT_ID);
        if (client == null || !client.isServiceAccountsEnabled()) {
            return false;
        }
        UserModel serviceAccount = session.users().getServiceAccount(client);
        return serviceAccount != null && serviceAccount.getId().equals(auth.getUser().getId());
    }

    public record CreateUser(String phone) {
    }
}
