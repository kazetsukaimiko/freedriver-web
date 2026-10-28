package io.freedriver.app.instances;

import java.util.List;

/**
 * Keycloak user stage. Create joins {@code mqtt-instance}. Reset replaces the credential
 * and does not read the previous password. List is the users in that group.
 */
public interface InstanceIdentity {

    String GROUP = "mqtt-instance";

    KeycloakUserResult create(KeycloakUser user);

    void reset(ResetPassword reset);

    List<InstanceSummary> list();
}
