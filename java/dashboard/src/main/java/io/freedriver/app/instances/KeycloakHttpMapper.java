package io.freedriver.app.instances;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Maps a Keycloak admin failure to {@link BrokerRefusedException}. The HTTP status is read here
 * and the response body stays unread.
 */
final class KeycloakHttpMapper {

    private KeycloakHttpMapper() {
    }

    static RuntimeException refused(RuntimeException failure) {
        if (failure instanceof WebApplicationException http) {
            Response response = http.getResponse();
            int status = response == null ? 0 : response.getStatus();
            return BrokerRefusedException.http(status);
        }
        if (failure instanceof ProcessingException) {
            return BrokerRefusedException.transport();
        }
        return failure;
    }
}
