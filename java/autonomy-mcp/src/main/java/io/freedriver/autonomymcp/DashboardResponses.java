package io.freedriver.autonomymcp;

import io.freedriver.dashboard.api.PendingCapHeader;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import org.jmolecules.architecture.hexagonal.SecondaryAdapter;

/**
 * Any dashboard status of 400 or more becomes {@link DashboardRefused}. The body stays unread.
 * {@link PendingCapHeader#FULL} on {@link PendingCapHeader#NAME} marks a full pending cap;
 * a missing header or any other value leaves it unmarked.
 */
@SecondaryAdapter
public class DashboardResponses implements ResponseExceptionMapper<DashboardRefused> {

    @Override
    public DashboardRefused toThrowable(Response response) {
        boolean pendingCap = PendingCapHeader.FULL.equals(response.getHeaderString(PendingCapHeader.NAME));
        return new DashboardRefused(response.getStatus(), pendingCap);
    }
}
