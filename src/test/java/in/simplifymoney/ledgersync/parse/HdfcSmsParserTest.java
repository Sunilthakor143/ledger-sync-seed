package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HdfcSmsParserTest {

    private HdfcSmsParser parser;

    @BeforeEach
    void setUp() {
        parser = new HdfcSmsParser();
    }

    @Test
    void supportsHdfcSenderOnly() {
        RawMessage valid = new RawMessage("m1", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1", "body");
        RawMessage invalidSender = new RawMessage("m2", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1", "body");
        RawMessage emailChannel = new RawMessage("m3", "email", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1", "body");

        assertTrue(parser.supports(valid));
        assertFalse(parser.supports(invalidSender));
        assertFalse(parser.supports(emailChannel));
    }

    @Test
    void parsesV1Debit() {
        RawMessage msg = new RawMessage("m10", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "Rs 99.99 debited from a/c **4821 on 01-07-26 at 11:52 to BIGBASKET. Avl Bal: Rs.93,111.41. Not you? Call 18002586161");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("99.99"), t.amount());
        assertEquals("BIGBASKET", t.merchant());
        assertEquals(new BigDecimal("93111.41"), t.statedBalance());
        assertEquals("m10", t.sourceMessageId());
        assertNotNull(t.occurredAt());
    }

    @Test
    void parsesV1Credit() {
        RawMessage msg = new RawMessage("m11", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "Rs.45,000.00 credited to a/c **4821 on 01-07-26 at 09:02 by SALARY CREDIT. Avl Bal: Rs.93,211.40");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(new BigDecimal("45000.00"), t.amount());
        assertEquals("SALARY CREDIT", t.merchant());
        assertEquals(new BigDecimal("93211.40"), t.statedBalance());
    }

    @Test
    void parsesV2Sent() {
        RawMessage msg = new RawMessage("m12", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "Sent INR675.67\nTo: IRCTC\nOn: 23 Jul 26 22:16\nA/c: XX4821\nAvailable Balance: INR 45679.37\n-HDFC Bank");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("675.67"), t.amount());
        assertEquals("IRCTC", t.merchant());
        assertEquals(new BigDecimal("45679.37"), t.statedBalance());
    }

    @Test
    void parsesV2Received() {
        RawMessage msg = new RawMessage("m13", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "Received INR1,250.33\nFrom: UPI/P2P/REFUND\nOn: 25 Jul 26 12:44\nA/c: XX4821\nAvailable Balance: INR 41841.53\n-HDFC Bank");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(new BigDecimal("1250.33"), t.amount());
        assertEquals("UPI/P2P/REFUND", t.merchant());
        assertEquals(new BigDecimal("41841.53"), t.statedBalance());
    }

    @Test
    void parsesCardTransactionWithNullStatedBalance() {
        RawMessage msg = new RawMessage("m14", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "Rs 1,249.99 spent on HDFC Bank Card x3310 at BLINKIT on 03-07-26 11:51. Avl Limit: Rs.196,250.03. Not you? Call 18002586161");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("3310", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("1249.99"), t.amount());
        assertEquals("BLINKIT", t.merchant());
        assertNull(t.statedBalance()); // Limit is not an account balance
    }

    @Test
    void rejectsEmandateAlert() {
        RawMessage msg = new RawMessage("m15", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "E-mandate! Rs.649.00 will be deducted from your HDFC Bank A/c XX4821 on 22-07-26 at 06:15 for NETFLIX ENTERTAINMENT. Avl Bal: Rs.46,868.04");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertFalse(parsed.isPresent());
    }

    @Test
    void rejectsOtpMessage() {
        RawMessage msg = new RawMessage("m16", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "268880 is your OTP for txn of Rs.5160.00 on HDFC Bank Card. Valid for 5 min. Do not share with anyone.");

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertFalse(parsed.isPresent());
    }
}
