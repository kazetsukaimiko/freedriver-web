package io.freedriver.sms.client;

import java.lang.reflect.Proxy;
import java.time.Duration;

/** Builds the {@link SmsClient} proxy. Package-private so callers cannot raise the timeout. */
final class SmsClients {

    private SmsClients() {
    }

    static SmsClient create(SmsClientConfig config, Duration timeout) {
        if (config == null || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new SmsClientException(0, "bad-request", null);
        }
        return (SmsClient) Proxy.newProxyInstance(
                SmsClient.class.getClassLoader(),
                new Class<?>[] {SmsClient.class},
                new SmsHttpHandler(config, timeout));
    }
}
