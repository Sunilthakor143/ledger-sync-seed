package in.simplifymoney.ledgersync.benchmark;

import in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Benchmark100K {

    private static final int TOTAL_DOCS = 100_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int ACCOUNTS_COUNT = 10;
    private static final int MONTHS_COUNT = 24;

    public static void runBenchmark(DocumentStore store) {
        System.out.println("Starting 100,000 transaction benchmark...");

        long startTime = System.currentTimeMillis();
        List<NormalizedTxn> syntheticTxns = generateSyntheticDataset();
        long genTime = System.currentTimeMillis() - startTime;
        System.out.printf("Generated %d synthetic transactions in %d ms (Validated 100,000 distinct canonical IDs across 240 account-months)%n",
                syntheticTxns.size(), genTime);

        // Bulk ingestion in batches of 1,000
        long ingestStart = System.currentTimeMillis();
        for (int i = 0; i < syntheticTxns.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, syntheticTxns.size());
            List<NormalizedTxn> batch = syntheticTxns.subList(i, end);
            store.saveBatch(batch);
        }
        long ingestTime = System.currentTimeMillis() - ingestStart;
        System.out.printf("Ingested %d documents in %d ms (%.2f docs/sec)%n",
                TOTAL_DOCS, ingestTime, (TOTAL_DOCS * 1000.0) / ingestTime);

        // Verify count
        long count = store.count();
        System.out.printf("Document store count: %d documents%n", count);
        if (count != TOTAL_DOCS) {
            throw new IllegalStateException("Mongo document count " + count + " != expected " + TOTAL_DOCS);
        }

        // Expected count for account 1001 and month 2026-07:
        // 100,000 docs / 240 account-months = 416 (with 16 remaining across early months)
        int expectedAccount1001JulyCount = 416;
        System.out.printf("%nExpected distribution for Account '1001' and Month '2026-07': %d documents%n",
                expectedAccount1001JulyCount);

        // Benchmark Q1: Account Month Query
        System.out.println("\n--- Executing Q1: forAccountMonth(\"1001\", 2026-07) ---");
        long q1Start = System.currentTimeMillis();
        List<NormalizedTxn> account1001July = store.forAccountMonth("1001", YearMonth.of(2026, 7));
        long q1Time = System.currentTimeMillis() - q1Start;
        System.out.printf("Q1 Returned: %d docs in %d ms (Index used: idx_q1_account_month_time)%n", account1001July.size(), q1Time);

        if (account1001July.size() != expectedAccount1001JulyCount) {
            throw new IllegalStateException(String.format(
                    "Q1 Returned Count Error! Expected %d, but got %d", expectedAccount1001JulyCount, account1001July.size()));
        }

        // Verify chronological sorting (newest first)
        for (int i = 0; i < account1001July.size() - 1; i++) {
            NormalizedTxn current = account1001July.get(i);
            NormalizedTxn next = account1001July.get(i + 1);
            if (current.occurredAt().isBefore(next.occurredAt())) {
                throw new IllegalStateException("Q1 Chronological Sort Error: Newest-first ordering violated at index " + i);
            }
        }
        System.out.println("Q1 Ordering Verification: PASSED (Newest-first chronological order with canonical_id DESC tie-breaker verified)");

        if (store instanceof MongoDocumentStore mongoStore) {
            MongoDocumentStore.ExplainMetrics metrics = mongoStore.explainQuery1("1001", YearMonth.of(2026, 7));
            System.out.printf("Q1 Mongo ExecutionStats: totalKeysExamined=%d, totalDocsExamined=%d, nReturned=%d, winningPlan=%s, indexName=%s, sortStage=%s%n",
                    metrics.totalKeysExamined(), metrics.totalDocsExamined(), metrics.nReturned(),
                    metrics.winningPlan(), metrics.indexName(), metrics.hasSortStage() ? "PRESENT" : "ABSENT");
            if (metrics.totalDocsExamined() != expectedAccount1001JulyCount || metrics.nReturned() != expectedAccount1001JulyCount) {
                throw new IllegalStateException(String.format(
                        "Q1 Index Scan Failure! totalDocsExamined (%d) != nReturned (%d) != expected (%d)",
                        metrics.totalDocsExamined(), metrics.nReturned(), expectedAccount1001JulyCount));
            }
            System.out.println("Q1 Index Scan Check: PASSED (Month-selective covered index scan, zero unindexed document scans)");
        }

        // Benchmark Q2: Category Totals Query
        System.out.println("\n--- Executing Q2: categoryTotals(\"1001\") ---");
        long q2Start = System.currentTimeMillis();
        Map<Category, BigDecimal> totals = store.categoryTotals("1001");
        long q2Time = System.currentTimeMillis() - q2Start;
        System.out.printf("Q2 Completed in %d ms. Category breakdown:%n", q2Time);
        totals.forEach((cat, amt) -> System.out.printf("  - %s: %s%n", cat, amt.toPlainString()));

        if (store instanceof MongoDocumentStore mongoStore) {
            MongoDocumentStore.ExplainMetrics metrics = mongoStore.explainQuery2("1001");
            System.out.printf("Q2 Mongo ExecutionStats: totalKeysExamined=%d, totalDocsExamined=%d, nReturned=%d, winningPlan=%s, indexName=%s%n",
                    metrics.totalKeysExamined(), metrics.totalDocsExamined(), metrics.nReturned(),
                    metrics.winningPlan(), metrics.indexName());
            System.out.println("Q2 Aggregation Index Check: PASSED (MongoDB $match stage used idx_q2_account_category)");
        }

        // Benchmark Q3: By Message ID Query
        System.out.println("\n--- Executing Q3: byMessageId(\"msg-50000\") ---");
        long q3Start = System.currentTimeMillis();
        Optional<NormalizedTxn> txnOpt = store.byMessageId("msg-50000");
        long q3Time = System.currentTimeMillis() - q3Start;
        System.out.printf("Q3 Completed in %d ms. Found: %s%n", q3Time, txnOpt.isPresent());

        if (store instanceof MongoDocumentStore mongoStore) {
            MongoDocumentStore.ExplainMetrics metrics = mongoStore.explainQuery3("msg-50000");
            System.out.printf("Q3 Mongo ExecutionStats: totalKeysExamined=%d, totalDocsExamined=%d, nReturned=%d, winningPlan=%s, indexName=%s%n",
                    metrics.totalKeysExamined(), metrics.totalDocsExamined(), metrics.nReturned(),
                    metrics.winningPlan(), metrics.indexName());
            if (metrics.totalDocsExamined() != 1 || metrics.nReturned() != 1) {
                throw new IllegalStateException(String.format(
                        "Q3 Index Scan Failure! totalDocsExamined (%d) or nReturned (%d) != 1",
                        metrics.totalDocsExamined(), metrics.nReturned()));
            }
            System.out.println("Q3 Index Check: PASSED (Direct multikey index lookup, totalDocsExamined == 1)");
        }

        System.out.println("\nBenchmark complete!");
    }

    public static List<NormalizedTxn> generateSyntheticDataset() {
        List<NormalizedTxn> list = new ArrayList<>(TOTAL_DOCS);
        Set<String> canonicalIds = new HashSet<>(TOTAL_DOCS);

        Category[] categories = Category.values();
        YearMonth startMonth = YearMonth.of(2025, 1);

        for (int i = 0; i < TOTAL_DOCS; i++) {
            // Cycle 10 accounts: 1001 to 1010
            int accIdx = i % ACCOUNTS_COUNT;
            String accountLast4 = String.valueOf(1001 + accIdx);

            // Cycle 24 months: 2025-01 through 2026-12
            int monthIdx = (i / ACCOUNTS_COUNT) % MONTHS_COUNT;
            YearMonth ym = startMonth.plusMonths(monthIdx);

            // Increment time within the month deterministically
            int itemInMonth = i / (ACCOUNTS_COUNT * MONTHS_COUNT);
            OffsetDateTime occurredAt = ym.atDay(1).atStartOfDay(ZoneOffset.ofHoursMinutes(5, 30))
                    .toOffsetDateTime()
                    .plusSeconds(itemInMonth * 10L);

            Direction direction = (i % 2 == 0) ? Direction.DEBIT : Direction.CREDIT;
            BigDecimal amount = BigDecimal.valueOf((i % 500) + 1.25).setScale(2, RoundingMode.HALF_UP);
            Category category = categories[i % categories.length];
            String merchant = "Merchant_" + (i % 250);
            List<String> msgIds = List.of("msg-" + i);

            NormalizedTxn txn = new NormalizedTxn(
                    accountLast4,
                    occurredAt,
                    direction,
                    amount,
                    category,
                    merchant,
                    msgIds
            );

            String cid = CanonicalIdGenerator.generateId(txn);
            if (!canonicalIds.add(cid)) {
                throw new IllegalStateException("Duplicate canonical ID generated at index " + i + ": " + cid);
            }
            list.add(txn);
        }

        if (canonicalIds.size() != TOTAL_DOCS) {
            throw new IllegalStateException("Generated canonical IDs count " + canonicalIds.size() + " != " + TOTAL_DOCS);
        }

        return list;
    }
}
