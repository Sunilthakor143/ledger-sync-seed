package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private IciciSmsParser parser;

    @BeforeEach
    void setUp() {
        parser = new IciciSmsParser();
    }

    @Test
    void supportsLegitimateSenderOnly() {
        RawMessage legitimate = new RawMessage("m1", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1", "body");
        RawMessage phishingSender = new RawMessage("m2", "sms", "VK-ICICIB", OffsetDateTime.now(), "dev1",
                "Dear Customer your ICICI netbanking will be suspended today. Verify PAN immediately at icicibank-secure.co/152459 to avoid debit of Rs.5126.00");

        assertTrue(parser.supports(legitimate));
        assertFalse(parser.supports(phishingSender), "VK-ICICIB must be unsupported at the supports() boundary");
    }

    @Test
    void rejectsVkIciciPhishingMessage() {
        RawMessage phishing = new RawMessage("m335", "sms", "VK-ICICIB", OffsetDateTime.now(), "dev1",
                "Dear Customer your ICICI netbanking will be suspended today. Verify PAN immediately at icicibank-secure.co/152459 to avoid debit of Rs.5126.00");

        assertFalse(parser.supports(phishing));
        assertEquals(Optional.empty(), parser.parse(phishing));
    }

    @Test
    void parsesV1Debit() {
        RawMessage msg = new RawMessage("m3", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "Dear Customer, Acct XX9075 is debited with INR 22.50 on 01/07/2026 10:22. Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("22.50"), t.amount());
        assertEquals("UPI/VEGETABLE VENDOR", t.merchant());
        assertEquals(new BigDecimal("31882.25"), t.statedBalance());
        assertNull(t.reference());
    }

    @Test
    void parsesV1Credit() {
        RawMessage msg = new RawMessage("m9", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "Dear Customer, Acct XX9075 is credited with INR 18,000 on 01/07/2026 21:14. Info: NEFT INWARD SELF. Avl Bal Rs.49,882.25 -ICICI Bank");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(new BigDecimal("18000.00"), t.amount());
        assertEquals("NEFT INWARD SELF", t.merchant());
        assertEquals(new BigDecimal("49882.25"), t.statedBalance());
    }

    @Test
    void parsesV2DebitWithReference() {
        RawMessage msg = new RawMessage("m162", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("5.00"), t.amount());
        assertEquals("UPI/BARBER", t.merchant());
        assertEquals("154245459403", t.reference());
        assertEquals(new BigDecimal("52841.30"), t.statedBalance());
    }

    @Test
    void parsesV2CreditWithReference() {
        RawMessage msg = new RawMessage("m161", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(new BigDecimal("1250.33"), t.amount());
        assertEquals("INTEREST CREDIT", t.merchant());
        assertEquals("424353460512", t.reference());
        assertEquals(new BigDecimal("52846.30"), t.statedBalance());
    }

    @Test
    void rejectsUpiMandateVerification() {
        RawMessage msg = new RawMessage("m182", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "ICICI Bank Acct XX9075 Dr INR 0.50 on 26-Jul-2026 10:05; UPI MANDATE VERIFY ref no 349951753536. BalAvl Rs 52,005.13");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertFalse(parsed.isPresent(), "UPI MANDATE VERIFY must be rejected as a non-transaction");
    }
}
