package io.freedriver.sms.http;

import io.freedriver.sms.PhoneMask;
import io.freedriver.sms.api.SmsApi;
import io.freedriver.sms.model.AgreementShown;
import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SendRequest;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.otp.OtpService;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.Optional;
import java.util.regex.Pattern;

public class SmsResource implements SmsApi {

    static final String CORRELATION_HEADER = "X-Freedriver-Sms-Cid";
    static final String MDC_CID = "smsCid";
    private static final Pattern CID = Pattern.compile("[0-9a-f]{8}");
    private static final Logger LOG = Logger.getLogger(SmsResource.class);

    private final OtpService otp;

    @Context
    HttpHeaders headers;

    @Inject
    public SmsResource(OtpService otp) {
        this.otp = otp;
    }

    @Override
    public SentResponse send(SendRequest request) {
        return switch (request) {
            case OtpSendRequest code -> {
                String cid = correlationId();
                LOG.infof("sms send last2=%s cid=%s", PhoneMask.mask(code.phone()), cid);
                MDC.put(MDC_CID, cid);
                try {
                    otp.send(code.phone(), Optional.ofNullable(code.agreement()).map(AgreementShown::wording));
                } finally {
                    MDC.remove(MDC_CID);
                }
                yield SentResponse.otp(code.phone());
            }
        };
    }

    private String correlationId() {
        if (headers == null) {
            return "-";
        }
        String raw = headers.getHeaderString(CORRELATION_HEADER);
        if (raw == null) {
            return "-";
        }
        String trimmed = raw.trim();
        return CID.matcher(trimmed).matches() ? trimmed : "-";
    }

    @Override
    public VerifiedResponse verify(VerifyRequest request) {
        otp.verify(request.phone(), request.code());
        return VerifiedResponse.otp(request.phone());
    }
}
