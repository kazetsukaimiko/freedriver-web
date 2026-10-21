package io.freedriver.sms.client;

import io.freedriver.sms.api.SmsApi;

import java.lang.reflect.Proxy;
import java.time.Duration;

/**
 * {@link SmsApi} as a {@link Proxy} over {@link java.net.http.HttpClient}.
 * The base URL is fixed ({@link SmsClientConfig#BASE_URL}, or loopback for tests), redirects are
 * never followed, and each call has a timeout. Nothing here logs headers or bodies.
 */
public interface SmsClient extends SmsApi {

    static SmsClient create(SmsClientConfig config) {
        return SmsClients.create(config, SmsClientConfig.TIMEOUT);
    }
}
