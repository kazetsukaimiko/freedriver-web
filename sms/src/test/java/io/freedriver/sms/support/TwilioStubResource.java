package io.freedriver.sms.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/** Fake Twilio Verify endpoint for Quarkus tests; the test profile points the base URL here. */
public class TwilioStubResource implements QuarkusTestResourceLifecycleManager {

    private static volatile WireMockServer server;

    public static WireMockServer server() {
        return server;
    }

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return Map.of("freedriver.sms.twilio.base-url", "http://localhost:" + server.port());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
