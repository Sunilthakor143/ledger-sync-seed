package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public static int run(SqlLedgerStore source, DocumentStore target) {
        return (int) new Backfill(source, target).run().written();
    }

    public Result run() {
        List<NormalizedTxn> sourceTxns = source.all();
        if (sourceTxns.isEmpty()) {
            return new Result(0, 0, 0);
        }

        // Sort records deterministically by accountLast4, occurredAt, direction, amount, merchant, canonicalId
        List<NormalizedTxn> sortedTxns = new ArrayList<>(sourceTxns);
        sortedTxns.sort(Comparator
                .comparing(NormalizedTxn::accountLast4)
                .thenComparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::direction)
                .thenComparing(NormalizedTxn::amount)
                .thenComparing(NormalizedTxn::merchant)
                .thenComparing(in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator::generateId));

        long readCount = sortedTxns.size();
        long writtenCount = 0;
        int batchSize = 100;

        for (int i = 0; i < sortedTxns.size(); i += batchSize) {
            List<NormalizedTxn> batch = sortedTxns.subList(i, Math.min(i + batchSize, sortedTxns.size()));
            target.saveBatch(batch);
            writtenCount += batch.size();
        }

        return new Result(readCount, writtenCount, 0);
    }

    public record Result(long read, long written, long skipped) {}
}
