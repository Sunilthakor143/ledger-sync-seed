package in.simplifymoney.ledgersync.store.mongo;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.StoredDocument;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MongoDocumentStoreTest {

    private static MongoDBContainer mongoContainer;
    private static MongoDocumentStore store;

    static {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.setProperty("docker.host", "npipe:////./pipe/docker_engine");
            System.setProperty("docker.client.strategy", "org.testcontainers.dockerclient.NpipeSocketClientProviderStrategy");
        }
        System.setProperty("testcontainers.checks.disable", "true");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @BeforeAll
    static void startMongo() {
        mongoContainer = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));
        mongoContainer.start();
        System.out.println("Testcontainers MongoDB container started at: " + mongoContainer.getReplicaSetUrl());
        store = new MongoDocumentStore(mongoContainer.getReplicaSetUrl(), "test_db");
    }

    @AfterAll
    static void stopMongo() {
        if (store != null) {
            store.close();
        }
        if (mongoContainer != null) {
            mongoContainer.stop();
        }
    }

    @Test
    void testSaveAndRetrieveAll() {
        NormalizedTxn t = new NormalizedTxn("1001", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "AMAZON", List.of("m-101"));

        store.save(t);
        List<NormalizedTxn> all = store.all();
        assertTrue(all.stream().anyMatch(txn -> txn.accountLast4().equals("1001")));

        List<StoredDocument> allStored = store.allStored();
        assertTrue(allStored.stream().anyMatch(doc -> doc.txn().accountLast4().equals("1001")));
        assertEquals(allStored.get(0).storageId(), allStored.get(0).canonicalId());
    }

    @Test
    void testQ1ForAccountMonthNewestFirst() {
        NormalizedTxn t1 = new NormalizedTxn("1002", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("50.00"), Category.SPEND, "STORE A", List.of("m-201"));
        NormalizedTxn t2 = new NormalizedTxn("1002", OffsetDateTime.parse("2026-07-15T14:30:00+05:30"),
                Direction.DEBIT, new BigDecimal("150.00"), Category.SPEND, "STORE B", List.of("m-202"));

        store.saveBatch(List.of(t1, t2));

        List<NormalizedTxn> monthTxns = store.forAccountMonth("1002", YearMonth.of(2026, 7));
        assertTrue(monthTxns.size() >= 2);
        assertEquals("STORE B", monthTxns.get(0).merchant(), "Newest transaction must appear first");
    }

    @Test
    void testQ1DeterministicTieBreakerOnSameInstant() {
        OffsetDateTime sameInstant = OffsetDateTime.parse("2026-07-10T12:00:00+05:30");
        NormalizedTxn t1 = new NormalizedTxn("1003", sameInstant, Direction.DEBIT, new BigDecimal("10.00"), Category.SPEND, "MERCHANT A", List.of("m-301"));
        NormalizedTxn t2 = new NormalizedTxn("1003", sameInstant, Direction.DEBIT, new BigDecimal("20.00"), Category.SPEND, "MERCHANT B", List.of("m-302"));

        store.saveBatch(List.of(t1, t2));

        List<NormalizedTxn> res1 = store.forAccountMonth("1003", YearMonth.of(2026, 7));
        List<NormalizedTxn> res2 = store.forAccountMonth("1003", YearMonth.of(2026, 7));

        assertEquals(res1.size(), res2.size());
        for (int i = 0; i < res1.size(); i++) {
            assertEquals(res1.get(i).merchant(), res2.get(i).merchant());
        }
    }

    @Test
    void testQ2CategoryTotals() {
        NormalizedTxn t1 = new NormalizedTxn("1004", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "STORE A", List.of("m-401"));
        NormalizedTxn t2 = new NormalizedTxn("1004", OffsetDateTime.parse("2026-07-02T11:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("200.00"), Category.SPEND, "STORE B", List.of("m-402"));

        store.saveBatch(List.of(t1, t2));

        Map<Category, BigDecimal> totals = store.categoryTotals("1004");
        assertEquals(new BigDecimal("300.00"), totals.get(Category.SPEND));
    }

    @Test
    void testQ3ByMessageId() {
        NormalizedTxn t = new NormalizedTxn("1005", OffsetDateTime.parse("2026-07-01T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("100.00"), Category.SPEND, "STORE A", List.of("m-unique-501"));

        store.save(t);
        Optional<NormalizedTxn> found = store.byMessageId("m-unique-501");
        assertTrue(found.isPresent());
        assertEquals("1005", found.get().accountLast4());
    }
}
