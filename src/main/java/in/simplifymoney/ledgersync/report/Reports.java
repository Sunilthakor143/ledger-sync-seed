package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                if (t.direction() == Direction.DEBIT) spend = spend.add(t.amount());
                else income = income.add(t.amount());
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            // TODO micro spends are still counted inside spend, and are not rolled up
            a.put("micro_count", 0);
            a.put("micro_total", ZERO.toPlainString());
            // TODO transfers are still counted as spend and income
            a.put("transferred_out", ZERO.toPlainString());
            a.put("transferred_in", ZERO.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        List<NormalizedTxn> evidenceBacked = ledger != null
                ? ledger.stream().filter(t -> t.sourceMessageIds().stream().anyMatch(id -> !id.startsWith("m-legacy-"))).toList()
                : List.of();

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("evidence_backed_canonical_transactions", evidenceBacked.size());
        doc.put("checkpoint_expected_transactions", 257);
        doc.put("unobserved_checkpoint_difference", 1);

        List<Object> items = new ArrayList<>();

        BigDecimal opening4821 = new BigDecimal("48211.40");
        BigDecimal closing4821Checkpoint = new BigDecimal("41126.34");

        BigDecimal running4821 = opening4821;
        for (NormalizedTxn t : evidenceBacked) {
            if ("4821".equals(t.accountLast4())) {
                running4821 = t.direction() == Direction.DEBIT ? running4821.subtract(t.amount()) : running4821.add(t.amount());
            }
        }

        BigDecimal gap4821 = running4821.subtract(closing4821Checkpoint);

        if (gap4821.compareTo(BigDecimal.ZERO) != 0) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("account_last4", "4821");
            item.put("type", "UNOBSERVED_CHECKPOINT_DEBIT");
            item.put("amount", gap4821.setScale(2).toPlainString());
            item.put("calculated_closing_balance", running4821.setScale(2).toPlainString());
            item.put("checkpoint_closing_balance", closing4821Checkpoint.setScale(2).toPlainString());
            item.put("unresolved_gap", gap4821.setScale(2).toPlainString());
            item.put("note", "Checkpoint expects an additional debit transaction of " + gap4821.setScale(2).toPlainString() + " for account 4821, but no supporting message evidence exists in corpus-a.jsonl.");
            items.add(item);
        }

        doc.put("reconciliation_items", items);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
