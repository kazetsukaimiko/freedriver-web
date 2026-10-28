package io.freedriver.app.instances;

/**
 * House provision's create payload. The password is the Keycloak credential.
 * {@link #toString()} omits it.
 */
public record KeycloakUser(
        String instanceId,
        String username,
        String label,
        String password,
        String publishTopic,
        String subscribeTopic,
        String group) {

    @Override
    public String toString() {
        return "KeycloakUser{instanceId=" + instanceId + ", label=" + label + ", group=" + group + "}";
    }
}
