package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.canonical.Canonicalizer;
import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IdempotentIngestTest {

    private Path corpusPath;

    @BeforeEach
    void setUp() {
        corpusPath = Path.of("fixtures/corpus-a.jsonl");
    }

    @Test
    void testA_singleIngestBaseline() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(corpusPath);

        Map<String, Long> accountCounts = store.all().stream()
                .collect(java.util.stream.Collectors.groupingBy(NormalizedTxn::accountLast4, java.util.stream.Collectors.counting()));

        System.err.println("IngestStats: " + stats + ", storeCount=" + store.count());
        System.err.println("AccountCounts: " + accountCounts);
        assertEquals(522, stats.messagesRead());
        assertEquals(256, stats.transactionsWritten());
        assertEquals(43, stats.messagesSkipped());
        assertEquals(256, store.count());
        assertEquals(145L, accountCounts.get("4821"));
        assertEquals(91L, accountCounts.get("9075"));
        assertEquals(20L, accountCounts.get("3310"));
    }

    @Test
    void testB_identicalSecondIngestProducesIdenticalLedger() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);

        ingest.ingestFile(corpusPath);
        List<NormalizedTxn> firstPass = new ArrayList<>(store.all());
        assertEquals(256, firstPass.size());

        ingest.ingestFile(corpusPath);
        List<NormalizedTxn> secondPass = new ArrayList<>(store.all());
        assertEquals(256, secondPass.size(), "Second ingestion must not add duplicate canonical transactions");

        for (int i = 0; i < firstPass.size(); i++) {
            NormalizedTxn t1 = firstPass.get(i);
            NormalizedTxn t2 = secondPass.get(i);
            assertEquals(t1.accountLast4(), t2.accountLast4());
            assertEquals(t1.occurredAt(), t2.occurredAt());
            assertEquals(t1.direction(), t2.direction());
            assertEquals(t1.amount(), t2.amount());
            assertEquals(t1.merchant(), t2.merchant());
            assertEquals(t1.sourceMessageIds(), t2.sourceMessageIds());
        }
    }

    @Test
    void testC_overlappingSubsetIngestExpandsSourceMessageIdsWithoutDuplicates() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();

        OffsetDateTime time = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
        NormalizedTxn initial = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("100.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "AMAZON", List.of("m-00001"));
        store.save(initial);
        assertEquals(1, store.count());

        NormalizedTxn overlapping = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("100.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "AMAZON", List.of("m-00314"));
        store.save(overlapping);

        assertEquals(1, store.count(), "Overlapping transaction must be merged into one canonical row");
        NormalizedTxn merged = store.all().get(0);
        assertEquals(List.of("m-00001", "m-00314"), merged.sourceMessageIds());
    }

    @Test
    void testD_reversedOrderIngestProducesByteEquivalentCanonicalResult() throws Exception {
        Parsers parsers = new Parsers();
        var msgs = IngestService.readCorpus(corpusPath);
        List<ParsedTxn> forwardEvidence = msgs.stream().map(parsers::parse).flatMap(java.util.Optional::stream).toList();

        List<ParsedTxn> reversedEvidence = new ArrayList<>(forwardEvidence);
        Collections.reverse(reversedEvidence);

        Canonicalizer canonicalizer = new Canonicalizer();
        List<NormalizedTxn> forwardResult = canonicalizer.canonicalize(forwardEvidence);
        List<NormalizedTxn> reversedResult = canonicalizer.canonicalize(reversedEvidence);

        assertEquals(forwardResult.size(), reversedResult.size());
        for (int i = 0; i < forwardResult.size(); i++) {
            NormalizedTxn f = forwardResult.get(i);
            NormalizedTxn r = reversedResult.get(i);
            assertEquals(f.accountLast4(), r.accountLast4());
            assertEquals(f.occurredAt(), r.occurredAt());
            assertEquals(f.direction(), r.direction());
            assertEquals(f.amount(), r.amount());
            assertEquals(f.category(), r.category());
            assertEquals(f.merchant(), r.merchant());
            assertEquals(f.sourceMessageIds(), r.sourceMessageIds());
        }
    }

    @Test
    void testE_blankToKnownMerchantEnrichment() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        OffsetDateTime time = OffsetDateTime.parse("2026-07-01T15:00:00+05:30");

        NormalizedTxn blankTxn = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "", List.of("m-blank1"));
        store.save(blankTxn);
        assertEquals(1, store.count());
        assertEquals("", store.all().get(0).merchant());

        NormalizedTxn knownTxn = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "SWIGGY", List.of("m-known1"));
        store.save(knownTxn);

        assertEquals(1, store.count());
        NormalizedTxn enriched = store.all().get(0);
        assertEquals("SWIGGY", enriched.merchant());
        assertEquals(List.of("m-blank1", "m-known1"), enriched.sourceMessageIds());
    }

    @Test
    void testF_knownToBlankIngestKeepsKnownMerchant() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        OffsetDateTime time = OffsetDateTime.parse("2026-07-01T15:00:00+05:30");

        NormalizedTxn knownTxn = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "SWIGGY", List.of("m-known1"));
        store.save(knownTxn);

        NormalizedTxn blankTxn = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "", List.of("m-blank1"));
        store.save(blankTxn);

        assertEquals(1, store.count());
        NormalizedTxn result = store.all().get(0);
        assertEquals("SWIGGY", result.merchant(), "Known merchant must not be overwritten by blank merchant");
        assertEquals(List.of("m-blank1", "m-known1"), result.sourceMessageIds());
    }

    @Test
    void testG_differentKnownMerchantsDoNotMerge() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        OffsetDateTime time = OffsetDateTime.parse("2026-07-01T15:00:00+05:30");

        NormalizedTxn merchantA = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "SWIGGY", List.of("m-1"));
        NormalizedTxn merchantB = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "ZOMATO", List.of("m-2"));

        store.save(merchantA);
        store.save(merchantB);

        assertEquals(2, store.count(), "Two different known merchants with same stable fields must not merge");
    }

    @Test
    void testH_repeatedEnrichmentRemainsStable() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        OffsetDateTime time = OffsetDateTime.parse("2026-07-01T15:00:00+05:30");

        NormalizedTxn blankTxn = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "", List.of("m-blank1"));
        NormalizedTxn knownTxn = new NormalizedTxn("4821", time, Direction.DEBIT, new BigDecimal("250.00"),
                in.simplifymoney.ledgersync.model.Category.SPEND, "SWIGGY", List.of("m-known1"));

        store.save(blankTxn);
        store.save(knownTxn);
        store.save(knownTxn);
        store.save(blankTxn);

        assertEquals(1, store.count());
        NormalizedTxn result = store.all().get(0);
        assertEquals("SWIGGY", result.merchant());
        assertEquals(List.of("m-blank1", "m-known1"), result.sourceMessageIds());
    }
}
