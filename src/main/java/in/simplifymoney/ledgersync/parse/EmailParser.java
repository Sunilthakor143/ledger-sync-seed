package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails for HDFC and ICICI.
 */
public final class EmailParser implements MessageParser {

    private static final Set<String> SUPPORTED_SENDERS = Set.of(
            "alerts@hdfcbank.net",
            "alerts@icicibank.com"
    );

    private static final Pattern TXN_ALERT = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with .*?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MERCHANT = Pattern.compile(
            "Merchant / Remarks:\\s*(?<merchant>.+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern REF = Pattern.compile(
            "Transaction reference:\\s*(?<ref>\\w+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_HEADER = Pattern.compile(
            "^Date:\\s*(?<date>.+)$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        if (m == null || !"email".equalsIgnoreCase(m.channel())) return false;
        String sender = m.sender() != null ? m.sender().trim().toLowerCase() : "";
        return SUPPORTED_SENDERS.contains(sender);
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        if (m == null || m.body() == null) return Optional.empty();
        String body = m.body();

        Matcher alert = TXN_ALERT.matcher(body);
        if (!alert.find()) {
            return Optional.empty();
        }

        String acct = alert.group("acct");
        Direction d = "debited".equalsIgnoreCase(alert.group("dir")) ? Direction.DEBIT : Direction.CREDIT;

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        OffsetDateTime occurredAt = null;
        Matcher dateMatcher = DATE_HEADER.matcher(body);
        if (dateMatcher.find()) {
            occurredAt = Dates.parse(dateMatcher.group("date"));
        }
        if (occurredAt == null) {
            occurredAt = m.receivedAt();
        }

        String merchant = "";
        Matcher merchantMatcher = MERCHANT.matcher(body);
        if (merchantMatcher.find()) {
            merchant = merchantMatcher.group("merchant").trim();
        }

        String ref = null;
        Matcher refMatcher = REF.matcher(body);
        if (refMatcher.find()) {
            ref = refMatcher.group("ref").trim();
        }

        BigDecimal statedBalance = Amounts.statedBalance(body);

        return Optional.of(new ParsedTxn(
                acct, occurredAt, d, amount, merchant, statedBalance, m.messageId(), ref));
    }
}
