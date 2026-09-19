package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS.
 *
 * TODO(ops): this only reads the "Dear Customer, Acct XX.... is debited with"
 * shape. There is at least one other ICICI format in the corpus that falls
 * straight through and is lost. Finish this.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\."
                    + "(?: Info: (?<merchant>[^.]+)\\.)?");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) (?:INR|Rs\\.?)\\s*.*? "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); (?<merchant>[^;]+?)"
                    + "(?: ref no (?<ref>\\w+))?\\. BalAvl",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equalsIgnoreCase(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        if (m == null || m.body() == null) return Optional.empty();
        String body = m.body();

        // Reject non-transactions (UPI mandate verification, OTPs, phishing/suspension alerts)
        if (body.contains("UPI MANDATE VERIFY") || body.contains("is your OTP")
                || body.contains("suspended") || body.contains("Verify PAN")) {
            return Optional.empty();
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            Direction d = "Dr".equalsIgnoreCase(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (amount == null || at == null) return Optional.empty();
            String merchant = v2.group("merchant").trim();
            String ref = v2.group("ref");
            return Optional.of(new ParsedTxn(
                    v2.group("acct"), at, d, amount, merchant,
                    Amounts.statedBalance(body), m.messageId(), ref));
        }

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            Direction d = "debited".equalsIgnoreCase(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();
            String rawMerchant = v1.group("merchant");
            String merchant = rawMerchant != null ? rawMerchant.trim() : "";
            return Optional.of(new ParsedTxn(
                    v1.group("acct"), at, d, amount, merchant,
                    Amounts.statedBalance(body), m.messageId(), null));
        }

        return Optional.empty();
    }
}
