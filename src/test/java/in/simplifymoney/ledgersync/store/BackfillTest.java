package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class BackfillTest {

    @TempDir
    Path tempDir;

    private SqlLedgerStore sqlStore;
    private InMemoryDocumentStore documentStore;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_ledger");
        sqlStore = new SqlLedgerStore(dbPath);
        sqlStore.migrate(Path.of("db/migration"));
        documentStore = new InMemoryDocumentStore();
    }

    @Test
    void testBackfillIdempotencyOnRepeatRun() {
        // Save test transactions to SQL store
        NormalizedTxn t1 = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", OffsetDateTime.parse("2026-07-02T12:00:00+05:30"),
                Direction.CREDIT, new BigDecimal("500.00"), Category.INCOME, "SALARY", List.of("m-2"));

        sqlStore.save(t1);
        sqlStore.save(t2);

        Backfill backfill = new Backfill(sqlStore, documentStore);
        Backfill.Result res1 = backfill.run();

        assertTrue(res1.read() >= 2);
        assertTrue(documentStore.count() >= 2);

        long countFirstPass = documentStore.count();

        // Run backfill again
        Backfill.Result res2 = backfill.run();
        assertEquals(countFirstPass, documentStore.count(), "Repeat backfill must not add duplicate documents");

        // Run third backfill
        backfill.run();
        assertEquals(countFirstPass, documentStore.count(), "Third backfill must produce zero logical changes");
    }

    @Test
    void testPartialFailureResumptionUsingFailingDecorator() {
        NormalizedTxn t1 = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", OffsetDateTime.parse("2026-07-02T12:00:00+05:30"),
                Direction.CREDIT, new BigDecimal("500.00"), Category.INCOME, "SALARY", List.of("m-2"));

        sqlStore.save(t1);
        sqlStore.save(t2);

        // Decorator that fails after batch 1
        AtomicInteger batchCount = new AtomicInteger(0);
        DocumentStore failingStore = new DocumentStore() {
            @Override
            public List<NormalizedTxn> forAccountMonth(String accountLast4, java.time.YearMonth month) {
                return documentStore.forAccountMonth(accountLast4, month);
            }

            @Override
            public java.util.Map<Category, BigDecimal> categoryTotals(String accountLast4) {
                return documentStore.categoryTotals(accountLast4);
            }

            @Override
            public java.util.Optional<NormalizedTxn> byMessageId(String messageId) {
                return documentStore.byMessageId(messageId);
            }

            @Override
            public void save(NormalizedTxn txn) {
                documentStore.save(txn);
            }

            @Override
            public void saveBatch(List<NormalizedTxn> transactions) {
                if (batchCount.incrementAndGet() > 1) {
                    throw new RuntimeException("Simulated batch failure");
                }
                documentStore.saveBatch(transactions);
            }

            @Override
            public List<NormalizedTxn> all() {
                return documentStore.all();
            }

            @Override
            public List<StoredDocument> allStored() {
                return documentStore.allStored();
            }

            @Override
            public long count() {
                return documentStore.count();
            }
        };

        Backfill failingBackfill = new Backfill(sqlStore, failingStore);
        try {
            failingBackfill.run();
        } catch (Exception expected) {
            // Expected simulated failure
        }

        // Run backfill with clean target store to completion
        Backfill cleanBackfill = new Backfill(sqlStore, documentStore);
        Backfill.Result res = cleanBackfill.run();

        assertTrue(res.read() >= 2);
        assertTrue(documentStore.count() >= 2);

        // Run again and assert idempotency
        long countAfterResume = documentStore.count();
        cleanBackfill.run();
        assertEquals(countAfterResume, documentStore.count());
    }
}
