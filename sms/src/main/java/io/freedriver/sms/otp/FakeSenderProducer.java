package io.freedriver.sms.otp;

import io.freedriver.sms.phones.ConsentLedger;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

/** The fake sender exists only in dev and test builds; a production build has no way to select it. */
@ApplicationScoped
@IfBuildProfile(anyOf = {"dev", "test"})
public class FakeSenderProducer {

    @Produces
    @Singleton
    @LookupIfProperty(name = "freedriver.sms.sender", stringValue = "fake")
    FakeSmsSender fakeSender(ConsentLedger consents) {
        return new FakeSmsSender(consents, Clock.systemUTC());
    }
}
