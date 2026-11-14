package io.freedriver.app.instances;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.net.URI;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Keycloak admin calls {@link KeycloakInstanceIdentity} makes, as the {@code freedriver-instances}
 * service account. A Keycloak error status or transport failure becomes {@link BrokerRefusedException};
 * a username Keycloak already has becomes {@link UsernameTakenException}. No JAX-RS type leaves this class.
 */
final class InstanceUsers {

    private final RealmResource realm;

    InstanceUsers(Supplier<Keycloak> admin, String realm) {
        this.realm = admin.get().realm(realm);
    }

    /** Creates the user and returns the id from the {@code Location} header. */
    String create(UserRepresentation user) {
        try (Response created = realm.users().create(user)) {
            if (created.getStatus() == 409) {
                throw new UsernameTakenException();
            }
            String id = created.getStatus() == 201 ? locationId(created.getLocation()) : null;
            if (id == null) {
                throw new BrokerRefusedException();
            }
            return id;
        } catch (UsernameTakenException | BrokerRefusedException domain) {
            throw domain;
        } catch (RuntimeException failure) {
            throw new BrokerRefusedException();
        }
    }

    UserRepresentation read(String id) {
        return refusing(() -> realm.users().get(id).toRepresentation());
    }

    List<UserRepresentation> exactUsername(String username) {
        return refusing(() -> realm.users().search(username, true));
    }

    List<GroupRepresentation> groupsNamed(String name) {
        return refusing(() -> realm.groups().groups(name, null, null));
    }

    List<GroupRepresentation> groupsOf(String userId) {
        return refusing(() -> realm.users().get(userId).groups());
    }

    List<UserRepresentation> members(String groupId, int first, int max) {
        return refusing(() -> realm.groups().group(groupId).members(first, max, false));
    }

    void join(String userId, String groupId) {
        refusing(() -> {
            realm.users().get(userId).joinGroup(groupId);
            return null;
        });
    }

    void resetPassword(String userId, CredentialRepresentation password) {
        refusing(() -> {
            realm.users().get(userId).resetPassword(password);
            return null;
        });
    }

    /** Deletes the user. A failed delete is not reported. */
    void deleteQuietly(String userId) {
        try (Response ignored = realm.users().delete(userId)) {
            // Nothing to read.
        } catch (RuntimeException ignored) {
            // The caller is already refusing; a failed delete must not hide that.
        }
    }

    private static <T> T refusing(Supplier<T> call) {
        try {
            return call.get();
        } catch (WebApplicationException | ProcessingException failure) {
            throw new BrokerRefusedException();
        }
    }

    private static String locationId(URI location) {
        String path = location == null || location.getRawPath() == null ? "" : location.getRawPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1 || location.getRawQuery() != null) {
            return null;
        }
        String id = path.substring(slash + 1);
        return id.isBlank() ? null : id;
    }
}
