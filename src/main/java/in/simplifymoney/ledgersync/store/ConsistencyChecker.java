package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator;
import in.simplifymoney.ledgersync.canonical.MerchantNormalizer;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        // Fetch SQL snapshot and group un-deduplicated SQL rows into canonical transactions
        List<NormalizedTxn> sqlTxns = sql.all();
        Map<String, StoredDocument> sqlMap = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlTxns) {
            String id = CanonicalIdGenerator.generateId(t);
            if (sqlMap.containsKey(id)) {
                StoredDocument existingDoc = sqlMap.get(id);
                NormalizedTxn existingTxn = existingDoc.txn();
                Set<String> mergedMsgIds = new TreeSet<>(existingTxn.sourceMessageIds());
                mergedMsgIds.addAll(t.sourceMessageIds());

                String bestMerchant = existingTxn.merchant().isEmpty() ? t.merchant() : existingTxn.merchant();
                NormalizedTxn mergedTxn = new NormalizedTxn(
                        existingTxn.accountLast4(),
                        existingTxn.occurredAt(),
                        existingTxn.direction(),
                        existingTxn.amount(),
                        existingTxn.category(),
                        bestMerchant,
                        new ArrayList<>(mergedMsgIds)
                );
                sqlMap.put(id, new StoredDocument(id, id, mergedTxn));
            } else {
                sqlMap.put(id, new StoredDocument(id, id, t));
            }
        }

        // Fetch Document Store snapshot
        List<StoredDocument> docStoredList = documents.allStored();
        Map<String, StoredDocument> docMap = new LinkedHashMap<>();

        for (StoredDocument doc : docStoredList) {
            // Storage Identity Integrity Verification
            if (doc.storageId() != null && doc.canonicalId() != null && !doc.storageId().equals(doc.canonicalId())) {
                divergences.add(new Divergence("STORAGE_IDENTITY_MISMATCH", doc.storageId(), doc.canonicalId()));
            }

            if (doc.txn() != null) {
                String recomputedId = CanonicalIdGenerator.generateId(doc.txn());
                if (doc.canonicalId() != null && !doc.canonicalId().equals(recomputedId)) {
                    divergences.add(new Divergence("FIELD_MISMATCH: canonicalIdentityTampered", recomputedId, doc.canonicalId()));
                }
            }

            if (doc.canonicalId() != null) {
                docMap.put(doc.canonicalId(), doc);
            }
        }

        // Check for Missing Documents (in SQL, missing in Document Store)
        for (Map.Entry<String, StoredDocument> entry : sqlMap.entrySet()) {
            String sqlId = entry.getKey();
            if (!docMap.containsKey(sqlId)) {
                divergences.add(new Divergence("MISSING_DOCUMENT", sqlId, "ABSENT"));
            }
        }

        // Check for Extra Documents (in Document Store, missing in SQL)
        for (Map.Entry<String, StoredDocument> entry : docMap.entrySet()) {
            String docId = entry.getKey();
            if (!sqlMap.containsKey(docId)) {
                divergences.add(new Divergence("EXTRA_DOCUMENT", "ABSENT", docId));
            }
        }

        // Field-by-Field Comparison for Matching Canonical IDs
        for (Map.Entry<String, StoredDocument> entry : sqlMap.entrySet()) {
            String id = entry.getKey();
            StoredDocument docStored = docMap.get(id);
            if (docStored == null || docStored.txn() == null) continue;

            NormalizedTxn s = entry.getValue().txn();
            NormalizedTxn d = docStored.txn();

            if (!s.accountLast4().equals(d.accountLast4())) {
                divergences.add(new Divergence("FIELD_MISMATCH: accountLast4", s.accountLast4(), d.accountLast4()));
            }
            if (!s.occurredAt().toString().equals(d.occurredAt().toString())) {
                divergences.add(new Divergence("FIELD_MISMATCH: occurredAt", s.occurredAt().toString(), d.occurredAt().toString()));
            }
            if (s.direction() != d.direction()) {
                divergences.add(new Divergence("FIELD_MISMATCH: direction", s.direction().name(), d.direction().name()));
            }
            if (s.amount().compareTo(d.amount()) != 0) {
                divergences.add(new Divergence("FIELD_MISMATCH: amount", s.amount().toPlainString(), d.amount().toPlainString()));
            }
            if (s.category() != d.category()) {
                divergences.add(new Divergence("FIELD_MISMATCH: category", s.category().name(), d.category().name()));
            }
            if (!Objects.equals(s.merchant(), d.merchant())) {
                divergences.add(new Divergence("FIELD_MISMATCH: merchant", s.merchant(), d.merchant()));
            }

            String sNorm = MerchantNormalizer.normalize(s.merchant());
            String dNorm = MerchantNormalizer.normalize(d.merchant());
            if (!sNorm.equals(dNorm)) {
                divergences.add(new Divergence("FIELD_MISMATCH: normalizedMerchant", sNorm, dNorm));
            }

            List<String> sIds = new ArrayList<>(s.sourceMessageIds());
            List<String> dIds = new ArrayList<>(d.sourceMessageIds());
            Collections.sort(sIds);
            Collections.sort(dIds);
            if (!sIds.equals(dIds)) {
                divergences.add(new Divergence("FIELD_MISMATCH: sourceMessageIds", String.join(",", sIds), String.join(",", dIds)));
            }
        }

        return Collections.unmodifiableList(divergences);
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
