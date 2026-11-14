package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.app.metrics.BlockingIo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Phone-sign-in membership through the {@code freedriver-invite} service account.
 * That account cannot grant {@code portal-admin}. This class sends only the phone.
 * The invite-account resource grants {@code dashboard} itself.
 */
@BlockingIo
@ApplicationScoped
public class KeycloakPhoneSignIn implements PhoneSignInDirectory {

    static final String GROUP = "phone-sign-in";
    private static final Pattern USERNAME = Pattern.compile("inv[0-9a-f]{32}");

    private final Supplier<Keycloak> admin;
    private final Supplier<InviteAccountsApi> accounts;
    private final String realm;
    private final boolean configured;

    @Inject
    KeycloakPhoneSignIn(@InviteAdmin Instance<Keycloak> admin,
                        @RestClient Instance<InviteAccountsApi> accounts,
                        @ConfigProperty(name = "quarkus.keycloak.admin-client.realm") String realm,
                        @ConfigProperty(name = "freedriver.invite.client-secret") Optional<String> clientSecret) {
        this(admin::get, accounts::get, realm, clientSecret.filter(secret -> !secret.isBlank()).isPresent());
    }

    KeycloakPhoneSignIn(Supplier<Keycloak> admin, Supplier<InviteAccountsApi> accounts, String realm,
                        boolean configured) {
        this.admin = admin;
        this.accounts = accounts;
        this.realm = realm;
        this.configured = configured;
    }

    @Override
    public boolean removeFromPhoneSignIn(String phone) {
        requireConfigured();
        RealmResource keycloak = keycloak();
        String groupId = call(() -> groupId(keycloak));
        String userId = call(() -> userId(keycloak, phone));
        if (userId == null) {
            return false;
        }
        try {
            keycloak.users().get(userId).leaveGroup(groupId);
        } catch (WebApplicationException e) {
            throw new InviteUpstreamException(
                    "Keycloak left the user in phone-sign-in (" + e.getResponse().getStatus() + ")");
        } catch (ProcessingException e) {
            throw new InviteUpstreamException("Keycloak call failed", e);
        }
        return true;
    }

    @Override
    public Created createPhoneSignInUser(String phone) {
        requireConfigured();
        InviteAccountsApi.InviteAccount account;
        try {
            account = accounts.get().create(realm, new InviteAccountsApi.InviteAccountRequest(phone));
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InviteUpstreamException("invite account create failed", e);
        }
        if (account == null || account.adminRoles() == null || !account.adminRoles().isEmpty()
                || account.groups() == null || !account.groups().equals(List.of(GROUP))
                || account.username() == null || !USERNAME.matcher(account.username()).matches()) {
            throw new InviteUpstreamException("invite account was not a plain phone-sign-in user");
        }
        return new Created(account.username(), account.groups(), account.adminRoles());
    }

    /** The one top-level {@code phone-sign-in} group. A group with a parent is not the policy group. */
    private static String groupId(RealmResource keycloak) {
        List<String> ids = keycloak.groups().groups(GROUP, null, null).stream()
                .filter(group -> GROUP.equals(group.getName()) && !hasParent(group))
                .map(GroupRepresentation::getId)
                .toList();
        if (ids.size() != 1 || ids.getFirst() == null || ids.getFirst().isBlank()) {
            throw new InviteUpstreamException("phone-sign-in group was not found");
        }
        return ids.getFirst();
    }

    /** The one user whose {@code phone} attribute is this number, or null. Arguments: first, max, enabled, exact, brief, q. */
    private static String userId(RealmResource keycloak, String phone) {
        List<UserRepresentation> users =
                keycloak.users().searchByAttributes(null, null, null, true, false, "phone:" + phone);
        if (users == null || users.isEmpty()) {
            return null;
        }
        if (users.size() != 1) {
            throw new InviteUpstreamException("more than one user has this phone attribute");
        }
        UserRepresentation user = users.getFirst();
        List<String> phones = user.getAttributes() == null ? null : user.getAttributes().get("phone");
        if (phones == null || !phones.contains(phone)) {
            throw new InviteUpstreamException("phone attribute does not match");
        }
        if (user.getId() == null || user.getId().isBlank()) {
            throw new InviteUpstreamException("phone user has no id");
        }
        return user.getId();
    }

    private static boolean hasParent(GroupRepresentation group) {
        return group.getParentId() != null && !group.getParentId().isBlank();
    }

    private RealmResource keycloak() {
        return admin.get().realm(realm);
    }

    private void requireConfigured() {
        if (!configured) {
            throw new InviteUpstreamException("invite client secret is unset");
        }
    }

    /** A Keycloak refusal or transport failure is upstream. */
    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (InviteUpstreamException e) {
            throw e;
        } catch (WebApplicationException e) {
            throw new InviteUpstreamException("Keycloak answered " + e.getResponse().getStatus());
        } catch (ProcessingException e) {
            throw new InviteUpstreamException("Keycloak call failed", e);
        }
    }
}
