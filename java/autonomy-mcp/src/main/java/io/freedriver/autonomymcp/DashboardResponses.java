package io.freedriver.autonomymcp;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

/** Any dashboard status of 400 or more becomes {@link DashboardRefused}. The body is not read. */
public class DashboardResponses implements ResponseExceptionMapper<DashboardRefused> {

    @Override
    public DashboardRefused toThrowable(Response response) {
        return new DashboardRefused(response.getStatus());
    }
}
