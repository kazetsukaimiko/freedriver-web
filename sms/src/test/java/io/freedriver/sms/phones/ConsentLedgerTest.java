package io.freedriver.sms.phones;

import io.freedriver.sms.support.Json;
import io.freedriver.sms.support.TestNumbers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentLedgerTest {

    @TempDir
    Path dataDir;

    @Test
    void records_survive_a_restart_with_number_wording_source_and_time() {
        String phone = TestNumbers.fresh();
        Instant at = Instant.parse("2026-09-27T16:00:00Z");
        new ConsentLedger(dataDir, Json.mapper()).record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES,
                TestNumbers.WORDING, ConsentSource.SEEDED_FIRST_SIGN_IN, at));

        ConsentLedger reloaded = new ConsentLedger(dataDir, Json.mapper());
        assertTrue(reloaded.hasAgreed(phone, ConsentPurpose.SIGN_IN_CODES));
        ConsentRecord record = reloaded.history(phone).getFirst();
        assertEquals(phone, record.phone());
        assertEquals(TestNumbers.WORDING, record.wording());
        assertEquals(ConsentSource.SEEDED_FIRST_SIGN_IN, record.source());
        assertEquals(at, record.givenAt());
        assertFalse(reloaded.hasAgreed(TestNumbers.fresh(), ConsentPurpose.SIGN_IN_CODES));
    }

    @Test
    void first_record_on_a_fresh_ledger_is_stored_once() {
        String phone = TestNumbers.fresh();
        ConsentLedger ledger = new ConsentLedger(dataDir, Json.mapper());
        ledger.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        assertEquals(1, ledger.history(phone).size());
        assertEquals(1, new ConsentLedger(dataDir, Json.mapper()).history(phone).size());
    }

    @Test
    void removing_a_number_from_the_list_keeps_its_history() {
        String phone = TestNumbers.fresh();
        PhoneDirectory directory = new PhoneDirectory(dataDir, Json.mapper());
        ConsentLedger ledger = new ConsentLedger(dataDir, Json.mapper());
        directory.add(new PhoneEntry(phone, "Mom", "mom.user", "dashboard", Instant.now()));
        ledger.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));

        assertTrue(directory.remove(phone));

        PhoneDirectory reloadedList = new PhoneDirectory(dataDir, Json.mapper());
        ConsentLedger reloadedLedger = new ConsentLedger(dataDir, Json.mapper());
        assertTrue(reloadedList.find(phone).isEmpty());
        assertEquals(1, reloadedLedger.history(phone).size());
    }

    @Test
    void phone_list_survives_a_restart() {
        String phone = TestNumbers.fresh();
        assertTrue(new PhoneDirectory(dataDir, Json.mapper())
                .add(new PhoneEntry(phone, "Mom", "mom.user", "dashboard", Instant.now())));
        PhoneDirectory reloaded = new PhoneDirectory(dataDir, Json.mapper());
        assertEquals("mom.user", reloaded.find(phone).orElseThrow().username());
        assertFalse(reloaded.add(new PhoneEntry(phone, "Other", "other", "dashboard", Instant.now())));
    }
}
