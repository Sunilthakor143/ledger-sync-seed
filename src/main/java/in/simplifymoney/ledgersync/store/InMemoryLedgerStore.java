package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.canonical.MerchantNormalizer;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Used by SelfCheck and by tests. Keeps everything it is given. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final List<NormalizedTxn> rows = new ArrayList<>();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        if (txn == null) return;
        String incomingNorm = MerchantNormalizer.normalize(txn.merchant());

        int targetIndex = -1;
        NormalizedTxn targetRow = null;

        for (int i = 0; i < rows.size(); i++) {
            NormalizedTxn r = rows.get(i);
            if (r.accountLast4().equals(txn.accountLast4())
                    && r.occurredAt().equals(txn.occurredAt())
                    && r.direction() == txn.direction()
                    && r.amount().compareTo(txn.amount()) == 0) {

                String existingNorm = MerchantNormalizer.normalize(r.merchant());
                if (existingNorm.equals(incomingNorm)) {
                    // Exact merchant match
                    targetIndex = i;
                    targetRow = r;
                    break;
                } else if (existingNorm.isEmpty() && !incomingNorm.isEmpty()) {
                    // Blank-to-known enrichment
                    targetIndex = i;
                    targetRow = r;
                    break;
                } else if (!existingNorm.isEmpty() && incomingNorm.isEmpty()) {
                    // Known-to-blank evidence
                    targetIndex = i;
                    targetRow = r;
                    break;
                }
            }
        }

        if (targetIndex >= 0 && targetRow != null) {
            Set<String> mergedIds = new HashSet<>(targetRow.sourceMessageIds());
            mergedIds.addAll(txn.sourceMessageIds());
            List<String> sortedIds = new ArrayList<>(mergedIds);
            Collections.sort(sortedIds);

            String existingNorm = MerchantNormalizer.normalize(targetRow.merchant());
            String finalMerchant = targetRow.merchant();
            if (existingNorm.isEmpty() && !incomingNorm.isEmpty()) {
                finalMerchant = txn.merchant();
            }

            NormalizedTxn updated = new NormalizedTxn(
                    targetRow.accountLast4(),
                    targetRow.occurredAt(),
                    targetRow.direction(),
                    targetRow.amount(),
                    targetRow.category(),
                    finalMerchant,
                    sortedIds);
            rows.set(targetIndex, updated);
        } else {
            // New transaction
            Set<String> uniqueIds = new HashSet<>(txn.sourceMessageIds());
            List<String> sortedIds = new ArrayList<>(uniqueIds);
            Collections.sort(sortedIds);
            NormalizedTxn newTxn = new NormalizedTxn(
                    txn.accountLast4(),
                    txn.occurredAt(),
                    txn.direction(),
                    txn.amount(),
                    txn.category(),
                    txn.merchant(),
                    sortedIds);
            rows.add(newTxn);
        }
    }

    @Override
    public synchronized List<NormalizedTxn> all() {
        return Collections.unmodifiableList(new ArrayList<>(rows));
    }

    @Override
    public synchronized long count() {
        return rows.size();
    }
}
