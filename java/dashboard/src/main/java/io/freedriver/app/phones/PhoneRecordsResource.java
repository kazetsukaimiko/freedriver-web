package io.freedriver.app.phones;

import io.freedriver.sms.model.PhoneRecord;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Portal-admin read of the stored phone rows, from sms, which owns them.
 * Same session as {@code /api/phones}.
 */
@ApplicationScoped
@Path("/api/phones/records")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("portal-admin")
public class PhoneRecordsResource {

    private final SmsRecords sms;

    @Inject
    public PhoneRecordsResource(SmsRecords sms) {
        this.sms = sms;
    }

    @GET
    public RecordList list() {
        return new RecordList(sms.phones());
    }

    public record RecordList(List<PhoneRecord> phones) {
    }
}
