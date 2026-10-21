package io.freedriver.sms.http;

import io.freedriver.sms.api.SmsApi;
import io.freedriver.sms.model.AgreementShown;
import io.freedriver.sms.model.OtpSendRequest;
import io.freedriver.sms.model.SendRequest;
import io.freedriver.sms.model.SentResponse;
import io.freedriver.sms.model.VerifiedResponse;
import io.freedriver.sms.model.VerifyRequest;
import io.freedriver.sms.otp.OtpService;
import jakarta.inject.Inject;

import java.util.Optional;

public class SmsResource implements SmsApi {

    private final OtpService otp;

    @Inject
    public SmsResource(OtpService otp) {
        this.otp = otp;
    }

    @Override
    public SentResponse send(SendRequest request) {
        return switch (request) {
            case OtpSendRequest code -> {
                otp.send(code.phone(), Optional.ofNullable(code.agreement()).map(AgreementShown::wording));
                yield SentResponse.otp(code.phone());
            }
        };
    }

    @Override
    public VerifiedResponse verify(VerifyRequest request) {
        otp.verify(request.phone(), request.code());
        return VerifiedResponse.otp(request.phone());
    }
}
