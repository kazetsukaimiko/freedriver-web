package io.freedriver.sms.api;

import io.freedriver.sms.model.AddPhone;
import io.freedriver.sms.model.Added;
import io.freedriver.sms.model.AttachUser;
import io.freedriver.sms.model.Attached;
import io.freedriver.sms.model.PhoneList;
import io.freedriver.sms.model.RemovePhone;
import io.freedriver.sms.model.Removed;
import io.freedriver.sms.model.WhitelistPhone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The portal's phone list. sms does not call Keycloak. Callers present the shared secret. */
@Path("/sms/phones")
@Produces(MediaType.APPLICATION_JSON)
public interface SmsPhonesApi {

    @GET
    PhoneList list();

    @POST
    @Path("remove")
    @Consumes(MediaType.APPLICATION_JSON)
    Removed remove(@Valid @NotNull RemovePhone request);

    /** Puts an invited number on the list after its code is verified. */
    @POST
    @Path("add")
    @Consumes(MediaType.APPLICATION_JSON)
    Added add(@Valid @NotNull AddPhone request);

    /** Lists a number before its Keycloak user exists. The role is always {@code dashboard}. */
    @POST
    @Path("whitelist")
    @Consumes(MediaType.APPLICATION_JSON)
    Added whitelist(@Valid @NotNull WhitelistPhone request);

    /** Records the username of the user created for a listed number. */
    @POST
    @Path("attach")
    @Consumes(MediaType.APPLICATION_JSON)
    Attached attach(@Valid @NotNull AttachUser request);
}
