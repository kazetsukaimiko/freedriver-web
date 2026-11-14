package io.freedriver.app.phones;

import io.freedriver.app.invites.InviteUpstreamException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** 409 from the invite-account resource is {@link PhoneAlreadyExists}; any other error status is upstream. */
public class InviteAccountResponses implements ResponseExceptionMapper<InviteUpstreamException> {

    @Override
    public InviteUpstreamException toThrowable(Response response) {
        if (response.getStatus() == 409) {
            return new PhoneAlreadyExists();
        }
        return new InviteUpstreamException("invite account create answered " + response.getStatus());
    }
}
