package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.canonical.Canonicalizer;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CanonicalizerTest {

    private Canonicalizer canonicalizer;
    private OffsetDateTime time1;

    @BeforeEach
    public void setUp() {
        canonicalizer = new Canonicalizer();
        time1 = OffsetDateTime.parse("2026-07-01T12:00:00+05:30");
    }

    @Test
    public void mergesDuplicateUploadsOfSameMessage() {
        ParsedTxn p1 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("99.99"), "BIGBASKET", null, "m-00001", null);
        ParsedTxn p2 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("99.99"), "BIGBASKET", null, "m-00314", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(p1, p2));
        assertEquals(1, txns.size());
        assertEquals(List.of("m-00001", "m-00314"), txns.get(0).sourceMessageIds());
    }

    @Test
    void mergesSmsAndEmailEvidenceForSameTransaction() {
        ParsedTxn sms = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("675.67"), "IRCTC", null, "m-00164", null);
        ParsedTxn email = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("675.67"), "IRCTC", null, "m-00165", "8085121323");

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(sms, email));
        assertEquals(1, txns.size());
        assertEquals(List.of("m-00164", "m-00165"), txns.get(0).sourceMessageIds());
    }

    @Test
    void sameDaySameAmountDifferentMerchantsRemainSeparate() {
        ParsedTxn t1 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("100.00"), "MERCHANT A", null, "m-1", null);
        ParsedTxn t2 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("100.00"), "MERCHANT B", null, "m-2", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(t1, t2));
        assertEquals(2, txns.size());
    }

    @Test
    void sameMerchantAmountDirectionDifferentTimestampsRemainSeparate() {
        OffsetDateTime time2 = time1.plusHours(1);
        ParsedTxn t1 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("100.00"), "MERCHANT A", null, "m-1", null);
        ParsedTxn t2 = new ParsedTxn("4821", time2, Direction.DEBIT, new BigDecimal("100.00"), "MERCHANT A", null, "m-2", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(t1, t2));
        assertEquals(2, txns.size());
    }

    @Test
    void fallbackReferenceCompatibilityRuleOnFallbackMatching() {
        ParsedTxn blank1 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("50.00"), "", null, "m-b1", "REF1");
        ParsedTxn knownConflicting = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("50.00"), "STORE A", null, "m-k1", "REF2");

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(blank1, knownConflicting));
        assertEquals(1, txns.size());
        assertEquals("STORE A", txns.get(0).merchant());
        assertEquals(List.of("m-b1", "m-k1"), txns.get(0).sourceMessageIds());
    }

    @Test
    void ambiguousFallbackWithTwoCandidatesDoesNotMerge() {
        ParsedTxn c1 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("50.00"), "STORE A", null, "m-c1", null);
        ParsedTxn c2 = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("50.00"), "STORE B", null, "m-c2", null);
        ParsedTxn blank = new ParsedTxn("4821", time1, Direction.DEBIT, new BigDecimal("50.00"), "", null, "m-blank", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(c1, c2, blank));
        assertEquals(3, txns.size());
        assertEquals(1, txns.stream().filter(t -> t.sourceMessageIds().contains("m-blank")).count());
    }

    @Test
    void testUtcEmailAndIstSmsSameInstant_producesSingleCanonicalTxn() {
        ParsedTxn emailUtc = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-18T18:50:00Z"), Direction.DEBIT, new BigDecimal("412.67"), "UBER INDIA", null, "m-00131", null);
        ParsedTxn smsIst = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), Direction.DEBIT, new BigDecimal("412.67"), "UBER INDIA", null, "m-00130", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(emailUtc, smsIst));
        assertEquals(1, txns.size());
        assertEquals(List.of("m-00130", "m-00131"), txns.get(0).sourceMessageIds());
        assertEquals(OffsetDateTime.parse("2026-07-18T18:50:00Z"), txns.get(0).occurredAt());
    }

    @Test
    void testReversedEvidenceOrder_producesIdenticalCanonicalResult() {
        ParsedTxn emailUtc = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-18T18:50:00Z"), Direction.DEBIT, new BigDecimal("412.67"), "Uber India", null, "m-00131", null);
        ParsedTxn smsIst = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), Direction.DEBIT, new BigDecimal("412.67"), "UBER INDIA", null, "m-00130", null);

        List<NormalizedTxn> order1 = canonicalizer.canonicalize(List.of(emailUtc, smsIst));
        List<NormalizedTxn> order2 = canonicalizer.canonicalize(List.of(smsIst, emailUtc));

        assertEquals(1, order1.size());
        assertEquals(1, order2.size());
        assertEquals(order1.get(0), order2.get(0));
        assertEquals(
                in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator.generateId(order1.get(0)),
                in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator.generateId(order2.get(0))
        );
    }

    @Test
    void testDuplicateReuploadPlusTimezoneVariant_unionsSourceMessageIds() {
        ParsedTxn sms1 = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), Direction.DEBIT, new BigDecimal("412.67"), "UBER INDIA", null, "m-00130", null);
        ParsedTxn sms2Dup = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), Direction.DEBIT, new BigDecimal("412.67"), "UBER INDIA", null, "m-00356", null);
        ParsedTxn emailUtc = new ParsedTxn("4821", OffsetDateTime.parse("2026-07-18T18:50:00Z"), Direction.DEBIT, new BigDecimal("412.67"), "UBER INDIA", null, "m-00131", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(sms1, sms2Dup, emailUtc));
        assertEquals(1, txns.size());
        assertEquals(List.of("m-00130", "m-00131", "m-00356"), txns.get(0).sourceMessageIds());
    }

    @Test
    void testSameInstantDifferentMerchants_remainsSeparate() {
        OffsetDateTime tUtc = OffsetDateTime.parse("2026-07-18T18:50:00Z");
        OffsetDateTime tIst = OffsetDateTime.parse("2026-07-19T00:20:00+05:30");

        ParsedTxn t1 = new ParsedTxn("4821", tUtc, Direction.DEBIT, new BigDecimal("412.67"), "SWIGGY", null, "m-1", null);
        ParsedTxn t2 = new ParsedTxn("4821", tIst, Direction.DEBIT, new BigDecimal("412.67"), "ZOMATO", null, "m-2", null);

        List<NormalizedTxn> txns = canonicalizer.canonicalize(List.of(t1, t2));
        assertEquals(2, txns.size());
    }
}
