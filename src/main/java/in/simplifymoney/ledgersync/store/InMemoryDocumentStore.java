package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of DocumentStore for fast unit testing.
 */
public class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, StoredDocument> store = new ConcurrentHashMap<>();

    @Override
    public synchronized List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        if (accountLast4 == null || month == null) return List.of();

        List<NormalizedTxn> list = store.values().stream()
                .map(StoredDocument::txn)
                .filter(t -> accountLast4.equals(t.accountLast4()) && month.equals(YearMonth.from(t.occurredAt().atZoneSameInstant(ZoneId.of("Asia/Kolkata")))))
                .sorted(Comparator.comparing(NormalizedTxn::occurredAt).reversed()
                        .thenComparing(t -> CanonicalIdGenerator.generateId(t), Comparator.reverseOrder()))
                .toList();

        return Collections.unmodifiableList(list);
    }

    @Override
    public synchronized Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        if (accountLast4 == null) return Map.of();

        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        store.values().stream()
                .map(StoredDocument::txn)
                .filter(t -> accountLast4.equals(t.accountLast4()))
                .forEach(t -> totals.merge(t.category(), t.amount(), BigDecimal::add));

        return Collections.unmodifiableMap(totals);
    }

    @Override
    public synchronized Optional<NormalizedTxn> byMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return Optional.empty();

        return store.values().stream()
                .map(StoredDocument::txn)
                .filter(t -> t.sourceMessageIds().contains(messageId))
                .findFirst();
    }

    @Override
    public synchronized void save(NormalizedTxn txn) {
        if (txn == null) return;
        saveBatch(List.of(txn));
    }

    @Override
    public synchronized void saveBatch(List<NormalizedTxn> transactions) {
        if (transactions == null || transactions.isEmpty()) return;
        for (NormalizedTxn t : transactions) {
            String id = CanonicalIdGenerator.generateId(t);
            if (store.containsKey(id)) {
                StoredDocument existing = store.get(id);
                NormalizedTxn eTxn = existing.txn();
                Set<String> mergedIds = new TreeSet<>(eTxn.sourceMessageIds());
                mergedIds.addAll(t.sourceMessageIds());
                String merchant = eTxn.merchant().isEmpty() ? t.merchant() : eTxn.merchant();
                NormalizedTxn merged = new NormalizedTxn(
                        eTxn.accountLast4(),
                        eTxn.occurredAt(),
                        eTxn.direction(),
                        eTxn.amount(),
                        eTxn.category(),
                        merchant,
                        new ArrayList<>(mergedIds)
                );
                store.put(id, new StoredDocument(id, id, merged));
            } else {
                store.put(id, new StoredDocument(id, id, t));
            }
        }
    }

    @Override
    public synchronized List<NormalizedTxn> all() {
        List<NormalizedTxn> list = store.values().stream().map(StoredDocument::txn).toList();
        return Collections.unmodifiableList(list);
    }

    @Override
    public synchronized List<StoredDocument> allStored() {
        return Collections.unmodifiableList(new ArrayList<>(store.values()));
    }

    public synchronized void putRawStoredDocument(StoredDocument doc) {
        if (doc != null && doc.canonicalId() != null) {
            store.put(doc.canonicalId(), doc);
        }
    }

    @Override
    public synchronized long count() {
        return store.size();
    }
}
