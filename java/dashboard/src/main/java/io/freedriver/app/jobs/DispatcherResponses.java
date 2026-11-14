package io.freedriver.app.jobs;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** Any dispatcher status of 400 or more becomes {@link DispatcherUnavailable}. The body is not read. */
public class DispatcherResponses implements ResponseExceptionMapper<DispatcherUnavailable> {

    @Override
    public DispatcherUnavailable toThrowable(Response response) {
        return new DispatcherUnavailable("dispatcher answered " + response.getStatus());
    }
}
