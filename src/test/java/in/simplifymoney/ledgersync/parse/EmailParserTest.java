package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private EmailParser parser;

    @BeforeEach
    void setUp() {
        parser = new EmailParser();
    }

    @Test
    void supportsHdfcAndIciciEmailSendersOnly() {
        RawMessage hdfc = new RawMessage("e1", "email", "alerts@hdfcbank.net", OffsetDateTime.now(), "dev1", "body");
        RawMessage icici = new RawMessage("e2", "email", "alerts@icicibank.com", OffsetDateTime.now(), "dev1", "body");
        RawMessage unsupported = new RawMessage("e3", "email", "news@promo.com", OffsetDateTime.now(), "dev1", "body");
        RawMessage smsChannel = new RawMessage("e4", "sms", "alerts@hdfcbank.net", OffsetDateTime.now(), "dev1", "body");

        assertTrue(parser.supports(hdfc));
        assertTrue(parser.supports(icici));
        assertFalse(parser.supports(unsupported));
        assertFalse(parser.supports(smsChannel));
    }

    @Test
    void parsesHdfcTransactionEmail() {
        String body = "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 4821 has been credited with INR 45,000.\n"
                + "Merchant / Remarks: SALARY CREDIT\n"
                + "Transaction reference: 1597155421\n\n"
                + "This is a system generated email.";
        RawMessage msg = new RawMessage("m2", "email", "alerts@hdfcbank.net", OffsetDateTime.now(), "dev1", body);

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(new BigDecimal("45000.00"), t.amount());
        assertEquals("SALARY CREDIT", t.merchant());
        assertEquals("1597155421", t.reference());
        assertNotNull(t.occurredAt());
    }

    @Test
    void parsesIciciTransactionEmail() {
        String body = "Date: Mon, 06 Jul 2026 11:25:00 +0530\n"
                + "Subject: Transaction alert on your account\n\n"
                + "Dear Customer,\n\n"
                + "Your account ending 9075 has been debited with Rs.129.67.\n"
                + "Merchant / Remarks: RELIANCE SMART\n"
                + "Transaction reference: 6576810104\n\n"
                + "This is a system generated email.";
        RawMessage msg = new RawMessage("m39", "email", "alerts@icicibank.com", OffsetDateTime.now(), "dev1", body);

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());
        ParsedTxn t = parsed.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("129.67"), t.amount());
        assertEquals("RELIANCE SMART", t.merchant());
        assertEquals("6576810104", t.reference());
    }

    @Test
    void rejectsPromotionalOrNonTransactionEmail() {
        String body = "Subject: Special Offer on Personal Loans!\n\n"
                + "Dear Customer,\nGet instant loan up to INR 5,00,000 at low interest rates. Apply now!";
        RawMessage msg = new RawMessage("m999", "email", "alerts@hdfcbank.net", OffsetDateTime.now(), "dev1", body);

        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertFalse(parsed.isPresent(), "Promotional email without transaction alert format must be rejected");
    }
}
