package io.freedriver.architecture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rules 1 to 4 for one module. Each rule picks its own subjects by role, so an API module, a utility
 * module and a service module all extend this class and run the same four. A module with layering
 * rows extends {@link LayeringTest}, which inherits these tests.
 */
public abstract class ApiQualityTest {

    @BeforeEach
    void onTheRecordedJdk() {
        ApiQuality.recordedJdk();
    }

    @Test
    void service_code_depends_on_the_allowlist() {
        ApiQuality.check(ApiQualityRules.serviceDependencies(), ApiQuality.main());
    }

    @Test
    void api_code_is_a_contract() {
        ApiQuality.check(ApiQualityRules.apiContracts(), ApiQuality.main());
    }

    @Test
    void implementations_are_implementation_code() {
        ApiQuality.check(ApiQualityRules.implementationsLiveInImpl(), ApiQuality.main());
    }

    @Test
    void implementation_packages_stay_private() {
        ApiQuality.check(ApiQualityRules.implementationPackagesStayPrivate(), ApiQuality.main());
    }
}
