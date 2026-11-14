package io.freedriver.sms.phones;

import io.freedriver.sms.support.TestNumbers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agreements in the {@code consents} collection on the Dev Services Mongo container. */
@QuarkusTest
class ConsentLedgerTest {

    @Inject
    ConsentLedger ledger;

    @Inject
    PhoneDirectory directory;

    @TempDir
    Path archive;

    @Test
    void a_record_keeps_number_wording_source_and_time() {
        String phone = TestNumbers.fresh();
        Instant at = Instant.parse("2026-09-27T16:00:00Z");
        ledger.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES,
                TestNumbers.WORDING, ConsentSource.SEEDED_FIRST_SIGN_IN, at));

        assertTrue(ledger.hasAgreed(phone, ConsentPurpose.SIGN_IN_CODES));
        ConsentRecord record = ledger.history(phone).getFirst();
        assertEquals(phone, record.phone());
        assertEquals(TestNumbers.WORDING, record.wording());
        assertEquals(ConsentSource.SEEDED_FIRST_SIGN_IN, record.source());
        assertEquals(at, record.givenAt());
        assertFalse(ledger.hasAgreed(TestNumbers.fresh(), ConsentPurpose.SIGN_IN_CODES));
    }

    @Test
    void each_record_is_stored_once() {
        String phone = TestNumbers.fresh();
        ledger.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));
        assertEquals(1, ledger.history(phone).size());
    }

    @Test
    void removing_a_number_from_the_list_keeps_its_history() {
        String phone = TestNumbers.fresh();
        directory.add(new PhoneEntry(phone, "Mom", "mom.user", "dashboard", Instant.now()));
        ledger.record(new ConsentRecord(phone, ConsentPurpose.SIGN_IN_CODES, TestNumbers.WORDING,
                ConsentSource.INVITE_PAGE, Instant.now()));

        assertTrue(directory.remove(phone));

        assertTrue(directory.find(phone).isEmpty());
        assertEquals(1, ledger.history(phone).size());
    }

    @Test
    void the_archive_imports_once_under_line_numbers_and_a_second_import_inserts_nothing() throws Exception {
        String first = TestNumbers.fresh();
        String second = TestNumbers.fresh();
        Path file = archive.resolve(ConsentLedger.FILE);
        Files.writeString(file,
                "{\"phone\":\"" + first + "\",\"purpose\":\"SIGN_IN_CODES\",\"wording\":\"kept\","
                        + "\"source\":\"INVITE_PAGE\",\"givenAt\":\"2026-09-27T16:00:00Z\",\"inviteId\":\"inv-1\"}\n"
                        + "\n"
                        + "{\"phone\":\"" + second + "\",\"purpose\":\"SIGN_IN_CODES\",\"wording\":\"older\","
                        + "\"source\":\"SEEDED_FIRST_SIGN_IN\",\"givenAt\":\"2026-09-20T08:00:00Z\"}\n",
                StandardCharsets.UTF_8);
        ledger.deleteById("1");
        ledger.deleteById("3");

        assertEquals(2, ledger.importFile(file));
        assertEquals(0, ledger.importFile(file));

        assertEquals("kept", ledger.findById("1").wording);
        assertEquals(second, ledger.findById("3").phone);
        ConsentRecord withoutInvite = ledger.history(second).getFirst();
        assertNull(withoutInvite.inviteId());
        assertEquals(Instant.parse("2026-09-20T08:00:00Z"), withoutInvite.givenAt());
        assertTrue(ledger.hasAgreed(first, ConsentPurpose.SIGN_IN_CODES));
        assertTrue(Files.exists(file), "the archive file stays in place");
    }

    @Test
    void an_import_that_stopped_after_the_first_line_resumes_on_the_next_start() throws Exception {
        String first = TestNumbers.fresh();
        String second = TestNumbers.fresh();
        String third = TestNumbers.fresh();
        ledger.deleteById("1");
        ledger.deleteById("2");
        ledger.deleteById("3");
        Path partial = archive.resolve("partial.jsonl");
        Files.writeString(partial, line(first) + "\n", StandardCharsets.UTF_8);
        assertEquals(1, ledger.importFile(partial));
        Path file = archive.resolve(ConsentLedger.FILE);
        Files.writeString(file, line(first) + "\n" + line(second) + "\n" + line(third) + "\n", StandardCharsets.UTF_8);

        assertEquals(2, ledger.importArchive(file));
        assertEquals(0, ledger.importArchive(file), "a finished import is not run again");

        assertEquals(first, ledger.findById("1").phone);
        assertEquals(second, ledger.findById("2").phone);
        assertEquals(third, ledger.findById("3").phone);
    }

    private static String line(String phone) {
        return "{\"phone\":\"" + phone + "\",\"purpose\":\"SIGN_IN_CODES\",\"wording\":\"kept\","
                + "\"source\":\"INVITE_PAGE\",\"givenAt\":\"2026-09-27T16:00:00Z\"}";
    }
}
