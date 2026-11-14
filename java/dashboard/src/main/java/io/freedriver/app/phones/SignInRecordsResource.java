package io.freedriver.app.phones;

import io.freedriver.sms.model.SignInRecord;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Portal-admin read of the stored sign-in rows, from sms, which owns them.
 * Same session as {@code /api/phones}.
 */
@ApplicationScoped
@Path("/api/sign-ins")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("portal-admin")
public class SignInRecordsResource {

    private final SmsRecords sms;

    @Inject
    public SignInRecordsResource(SmsRecords sms) {
        this.sms = sms;
    }

    @GET
    public RecordList list() {
        return new RecordList(sms.signIns());
    }

    public record RecordList(List<SignInRecord> signIns) {
    }
}
