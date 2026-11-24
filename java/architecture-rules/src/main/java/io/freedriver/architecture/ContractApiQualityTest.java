package io.freedriver.architecture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rules 2 and 3 for one API module. */
public abstract class ContractApiQualityTest {

    @BeforeEach
    void onTheRecordedJdk() {
        ApiQuality.recordedJdk();
    }

    @Test
    void api_modules_are_contracts() {
        ApiQuality.check(ApiQualityRules.apiContracts(), ApiQuality.main());
    }

    @Test
    void implementations_live_in_impl_modules() {
        ApiQuality.check(ApiQualityRules.implementationsLiveInImpl(), ApiQuality.main());
    }
}
