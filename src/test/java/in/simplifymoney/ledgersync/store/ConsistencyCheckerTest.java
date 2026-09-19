package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator;
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

public class ConsistencyCheckerTest {

    @TempDir
    Path tempDir;

    private SqlLedgerStore sqlStore;
    private InMemoryDocumentStore documentStore;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_ledger_cc");
        sqlStore = new SqlLedgerStore(dbPath);
        sqlStore.migrate(Path.of("db/migration"));
        documentStore = new InMemoryDocumentStore();
    }

    @Test
    void testZeroDivergenceOnIdenticalStores() {
        NormalizedTxn t1 = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));

        sqlStore.save(t1);
        Backfill.run(sqlStore, documentStore);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, documentStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        if (!divergences.isEmpty()) {
            for (ConsistencyChecker.Divergence d : divergences) {
                System.err.println("Unexpected divergence: " + d);
            }
        }
        assertTrue(divergences.isEmpty(), "Matching stores must report 0 divergences");
    }

    @Test
    void testDetectsMissingAndExtraDocuments() {
        NormalizedTxn t1 = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", OffsetDateTime.parse("2026-07-02T12:00:00+05:30"),
                Direction.CREDIT, new BigDecimal("500.00"), Category.INCOME, "SALARY", List.of("m-2"));

        sqlStore.save(t1);
        documentStore.save(t2);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, documentStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty());
        assertTrue(divergences.stream().anyMatch(d -> "MISSING_DOCUMENT".equals(d.what())));
        assertTrue(divergences.stream().anyMatch(d -> "EXTRA_DOCUMENT".equals(d.what())));
    }

    @Test
    void testDetectsFieldMismatches() {
        NormalizedTxn tSql = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));

        NormalizedTxn tDoc = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("200.00"), Category.SPEND, "AMAZON", List.of("m-1"));

        sqlStore.save(tSql);

        // Put doc into document store using tSql's id
        String id = CanonicalIdGenerator.generateId(tSql);
        documentStore.putRawStoredDocument(new StoredDocument(id, id, tDoc));

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, documentStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty());
        assertTrue(divergences.stream().anyMatch(d -> d.what().startsWith("FIELD_MISMATCH")));
    }

    @Test
    void testDetectsStorageIdVsCanonicalIdMismatch() {
        NormalizedTxn t = new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-1"));

        sqlStore.save(t);

        String canonicalId = CanonicalIdGenerator.generateId(t);
        String corruptedStorageId = "corrupted-storage-id-xyz";

        // Inject document where storageId != canonicalId
        documentStore.putRawStoredDocument(new StoredDocument(corruptedStorageId, canonicalId, t));

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, documentStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty());
        assertTrue(divergences.stream().anyMatch(d -> "STORAGE_IDENTITY_MISMATCH".equals(d.what())));
    }
}
