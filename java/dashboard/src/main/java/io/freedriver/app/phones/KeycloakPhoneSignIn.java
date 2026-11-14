package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import io.freedriver.app.metrics.BlockingIo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.keycloak.admin.client.Keycloak;
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

    private final InviteUsers keycloak;
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
        this.keycloak = new InviteUsers(admin, accounts, realm);
        this.configured = configured;
    }

    @Override
    public boolean removeFromPhoneSignIn(String phone) {
        requireConfigured();
        String groupId = groupId(keycloak);
        String userId = userId(keycloak, phone);
        if (userId == null) {
            return false;
        }
        keycloak.leaveGroup(userId, groupId);
        return true;
    }

    @Override
    public Created createPhoneSignInUser(String phone) {
        requireConfigured();
        InviteAccountsApi.InviteAccount account = keycloak.createAccount(phone);
        if (account == null || account.adminRoles() == null || !account.adminRoles().isEmpty()
                || account.groups() == null || !account.groups().equals(List.of(GROUP))
                || account.username() == null || !USERNAME.matcher(account.username()).matches()) {
            throw new InviteUpstreamException("invite account was not a plain phone-sign-in user");
        }
        return new Created(account.username(), account.groups(), account.adminRoles());
    }

    /** The one top-level {@code phone-sign-in} group. A group with a parent is not the policy group. */
    private static String groupId(InviteUsers keycloak) {
        List<String> ids = keycloak.groupsNamed(GROUP).stream()
                .filter(group -> GROUP.equals(group.getName()) && !hasParent(group))
                .map(GroupRepresentation::getId)
                .toList();
        if (ids.size() != 1 || ids.getFirst() == null || ids.getFirst().isBlank()) {
            throw new InviteUpstreamException("phone-sign-in group was not found");
        }
        return ids.getFirst();
    }

    /** The one user whose {@code phone} attribute is this number, or null when there is none. */
    private static String userId(InviteUsers keycloak, String phone) {
        List<UserRepresentation> users = keycloak.exactAttribute("phone:" + phone);
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

    private void requireConfigured() {
        if (!configured) {
            throw new InviteUpstreamException("invite client secret is unset");
        }
    }

}
