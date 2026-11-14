package io.freedriver.sms.http;

import io.freedriver.sms.api.SmsSignInsApi;
import io.freedriver.sms.model.SignInRecords;
import io.freedriver.sms.phones.SignInRepository;
import jakarta.inject.Inject;

/** The stored sign-in times for the portal. Callers present the shared secret. */
public class SignInRecordsResource implements SmsSignInsApi {

    @Inject
    SignInRepository signIns;

    @Override
    public SignInRecords list() {
        return new SignInRecords(signIns.records());
    }
}
