package io.freedriver.architecture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rules 1 and 3 for one service module. The four modules that already extend {@link LayeringTest}
 * inherit these tests. A module with no layering rows extends this class directly.
 */
public abstract class ServiceApiQualityTest {

    @BeforeEach
    void onTheRecordedJdk() {
        ApiQuality.recordedJdk();
    }

    @Test
    void service_code_depends_on_the_allowlist() {
        ApiQuality.check(ApiQualityRules.serviceDependencies(), ApiQuality.main());
    }

    @Test
    void implementations_live_in_impl_modules() {
        ApiQuality.check(ApiQualityRules.implementationsLiveInImpl(), ApiQuality.main());
    }
}
