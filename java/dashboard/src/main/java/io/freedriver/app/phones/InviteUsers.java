package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.function.Supplier;

/**
 * The Keycloak calls {@link KeycloakPhoneSignIn} makes, as the {@code freedriver-invite} service
 * account. A Keycloak error status or transport failure becomes {@link InviteUpstreamException}; a
 * second user for one phone stays {@link PhoneAlreadyExists}. No JAX-RS type leaves this class.
 */
final class InviteUsers {

    private final Supplier<Keycloak> admin;
    private final Supplier<InviteAccountsApi> accounts;
    private final String realm;

    InviteUsers(Supplier<Keycloak> admin, Supplier<InviteAccountsApi> accounts, String realm) {
        this.admin = admin;
        this.accounts = accounts;
        this.realm = realm;
    }

    List<GroupRepresentation> groupsNamed(String name) {
        return call(() -> realm().groups().groups(name, null, null));
    }

    /** Users whose attribute query matches exactly, full representations. Arguments: first, max, enabled, exact, brief, q. */
    List<UserRepresentation> exactAttribute(String query) {
        return call(() -> realm().users().searchByAttributes(null, null, null, true, false, query));
    }

    void leaveGroup(String userId, String groupId) {
        try {
            realm().users().get(userId).leaveGroup(groupId);
        } catch (WebApplicationException e) {
            throw new InviteUpstreamException(
                    "Keycloak left the user in phone-sign-in (" + e.getResponse().getStatus() + ")");
        } catch (ProcessingException e) {
            throw new InviteUpstreamException("Keycloak call failed", e);
        }
    }

    InviteAccountsApi.InviteAccount createAccount(String phone) {
        try {
            return accounts.get().create(realm, new InviteAccountsApi.InviteAccountRequest(phone));
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InviteUpstreamException("invite account create failed", e);
        }
    }

    private RealmResource realm() {
        return admin.get().realm(realm);
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (WebApplicationException e) {
            throw new InviteUpstreamException("Keycloak answered " + e.getResponse().getStatus());
        } catch (ProcessingException e) {
            throw new InviteUpstreamException("Keycloak call failed", e);
        }
    }
}
