package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.RawMessage;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NonTransactionRejectionTest {

    private Parsers parsers;

    @BeforeEach
    void setUp() {
        parsers = new Parsers();
    }

    @Test
    void rejectsOtpMessage() {
        RawMessage otp = new RawMessage("m-320", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "268880 is your OTP for txn of Rs.5160.00 on HDFC Bank Card. Valid for 5 min. Do not share with anyone.");

        Optional<ParsedTxn> res = parsers.parse(otp);
        assertTrue(res.isEmpty(), "OTP message must not be parsed as transaction");
    }

    @Test
    void rejectsPhishingMessageFromVkIcicib() {
        RawMessage phishing = new RawMessage("m-335", "sms", "VK-ICICIB", OffsetDateTime.now(), "dev1",
                "Dear Customer your ICICI netbanking will be suspended today. Verify PAN immediately at icicibank-secure.co/152459 to avoid debit of Rs.5126.00");

        Optional<ParsedTxn> res = parsers.parse(phishing);
        assertTrue(res.isEmpty(), "Phishing message from VK-ICICIB must be rejected at parser level");
    }

    @Test
    void parsesUpiMandateVerificationTransactionInr50() {
        RawMessage mandateTxn = new RawMessage("m-182", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "ICICI Bank Acct XX9075 Dr INR 0.50 on 26-Jul-2026 10:05; UPI MANDATE VERIFY ref no 349951753536. BalAvl Rs 52,005.13");

        Optional<ParsedTxn> res = parsers.parse(mandateTxn);
        assertTrue(res.isPresent(), "Legitimate ICICI V2 transaction message with UPI MANDATE VERIFY merchant must be parsed");
        assertEquals("9075", res.get().accountLast4());
        assertEquals(new java.math.BigDecimal("0.50"), res.get().amount());
        assertEquals("UPI MANDATE VERIFY", res.get().merchant());
    }

    @Test
    void rejectsNonTransactionMandateVerificationAlert() {
        RawMessage mandateAlert = new RawMessage("m-alert", "sms", "VM-ICICIB-T", OffsetDateTime.now(), "dev1",
                "Mandate verification alert: Please verify your mandate at icicibank.com");

        Optional<ParsedTxn> res = parsers.parse(mandateAlert);
        assertTrue(res.isEmpty(), "Non-transaction mandate verification alert lacking transaction structure must be rejected");
    }

    @Test
    void rejectsEmandateNotification() {
        RawMessage emandate = new RawMessage("m-153", "sms", "AD-HDFCBK-S", OffsetDateTime.now(), "dev1",
                "E-mandate! Rs.649.00 will be deducted from your HDFC Bank A/c XX4821 on 22-07-26 at 06:15 for NETFLIX ENTERTAINMENT. Avl Bal: Rs.46,868.04");

        Optional<ParsedTxn> res = parsers.parse(emandate);
        assertTrue(res.isEmpty(), "E-mandate future deduction notification must not be treated as completed transaction");
    }

    @Test
    void rejectsDeliveryNotification() {
        RawMessage delivery = new RawMessage("m-314", "sms", "BP-DELHVY", OffsetDateTime.now(), "dev1",
                "Your order is out for delivery and will arrive by 7 PM. Track: dlhvry.in/9856586");

        Optional<ParsedTxn> res = parsers.parse(delivery);
        assertTrue(res.isEmpty(), "Delivery notifications must be rejected");
    }

    @Test
    void rejectsUnrelatedMoneyMentionEmail() {
        RawMessage email = new RawMessage("m-999", "email", "alerts@hdfcbank.net", OffsetDateTime.now(), "dev1",
                "Subject: Win Rs 1000 Cashback!\n\nUse your credit card today to win exciting cashback!");

        Optional<ParsedTxn> res = parsers.parse(email);
        assertTrue(res.isEmpty(), "Promotional money mention email must be rejected");
    }
}
