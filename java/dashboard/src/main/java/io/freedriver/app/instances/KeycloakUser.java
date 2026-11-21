package io.freedriver.app.instances;

/**
 * House provision's create payload. The password is the Keycloak credential.
 * The Keycloak user stage derives {@code mqtt_publ_topic} and {@code mqtt_subs_topic}
 * from the house id. {@link #toString()} omits the password.
 */
public record KeycloakUser(
        String instanceId,
        String username,
        String label,
        String password,
        String group) {

    @Override
    public String toString() {
        return "KeycloakUser{instanceId=" + instanceId + ", label=" + label + ", group=" + group + "}";
    }
}
