package io.freedriver.dispatcher.api;

import io.freedriver.dispatcher.model.AssignmentPage;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The dispatcher's service-to-service admin surface. Its callers are services with their own
 * credentials, so this contract carries no role: the dispatcher's resource class requires
 * {@code dispatcher-admin}. A REST client built from this interface checks nothing about its own caller.
 */
@Path("/api/dispatcher/admin")
@Produces(MediaType.APPLICATION_JSON)
public interface DispatcherAdminApi {

    /**
     * One page of assignments across all callers, newest first, for the portal's jobs page.
     * {@code limit} is 1 through 200; another value is 400. {@code cursor} is the previous page's {@code next}.
     */
    @GET
    @Path("/jobs")
    AssignmentPage jobs(@QueryParam("limit") @DefaultValue("100") int limit,
                        @QueryParam("cursor") String cursor);
}
