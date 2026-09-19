package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.*;
import in.simplifymoney.ledgersync.canonical.CanonicalIdGenerator;
import in.simplifymoney.ledgersync.canonical.MerchantNormalizer;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * Production MongoDB implementation of DocumentStore.
 */
public class MongoDocumentStore implements DocumentStore, AutoCloseable {

    public static final String DEFAULT_URI = "mongodb://localhost:27017";
    public static final String DEFAULT_DB = "ledgersync";
    public static final String COLLECTION_NAME = "transactions";

    private final MongoClient client;
    private final MongoDatabase db;
    private final MongoCollection<Document> collection;

    public MongoDocumentStore() {
        this(DEFAULT_URI, DEFAULT_DB);
    }

    public MongoDocumentStore(String connectionUri, String dbName) {
        this.client = MongoClients.create(connectionUri);
        this.db = client.getDatabase(dbName);
        this.collection = db.getCollection(COLLECTION_NAME);
        initIndexes();
    }

    private void initIndexes() {
        // Q1 Index: account_last4 + year_month + occurred_at_epoch_nanos DESC + canonical_id DESC
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("account_last4"),
                        Indexes.ascending("year_month"),
                        Indexes.descending("occurred_at_epoch_nanos"),
                        Indexes.descending("canonical_id")
                ),
                new IndexOptions().name("idx_q1_account_month_time")
        );

        // Q2 Index: account_last4 + category
        collection.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("account_last4"),
                        Indexes.ascending("category")
                ),
                new IndexOptions().name("idx_q2_account_category")
        );

        // Q3 Index: source_message_ids multikey index
        collection.createIndex(
                Indexes.ascending("source_message_ids"),
                new IndexOptions().name("idx_q3_source_message_ids")
        );
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        if (accountLast4 == null || month == null) return List.of();

        Bson filter = Filters.and(
                Filters.eq("account_last4", accountLast4),
                Filters.eq("year_month", month.toString())
        );

        Bson sort = Indexes.compoundIndex(
                Indexes.descending("occurred_at_epoch_nanos"),
                Indexes.descending("canonical_id")
        );

        List<NormalizedTxn> result = new ArrayList<>();
        for (Document doc : collection.find(filter).sort(sort)) {
            result.add(toNormalizedTxn(doc));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        if (accountLast4 == null) return Map.of();

        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.eq("account_last4", accountLast4)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount"))
        );

        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        for (Document doc : collection.aggregate(pipeline)) {
            String catStr = doc.getString("_id");
            Decimal128 sumVal = doc.get("total", Decimal128.class);
            if (catStr != null && sumVal != null) {
                Category cat = Category.valueOf(catStr);
                totals.put(cat, sumVal.bigDecimalValue().setScale(2, RoundingMode.HALF_UP));
            }
        }
        return totals;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) return Optional.empty();

        Bson filter = Filters.eq("source_message_ids", messageId);
        Document doc = collection.find(filter).first();
        return doc != null ? Optional.of(toNormalizedTxn(doc)) : Optional.empty();
    }

    @Override
    public void save(NormalizedTxn txn) {
        if (txn == null) return;
        saveBatch(List.of(txn));
    }

    @Override
    public void saveBatch(List<NormalizedTxn> transactions) {
        if (transactions == null || transactions.isEmpty()) return;

        Map<String, NormalizedTxn> grouped = new LinkedHashMap<>();
        for (NormalizedTxn t : transactions) {
            String canonicalId = CanonicalIdGenerator.generateId(t);
            if (grouped.containsKey(canonicalId)) {
                NormalizedTxn existing = grouped.get(canonicalId);
                Set<String> mergedIds = new TreeSet<>(existing.sourceMessageIds());
                mergedIds.addAll(t.sourceMessageIds());
                String merchant = existing.merchant().isEmpty() ? t.merchant() : existing.merchant();
                NormalizedTxn merged = new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        merchant,
                        new ArrayList<>(mergedIds)
                );
                grouped.put(canonicalId, merged);
            } else {
                grouped.put(canonicalId, t);
            }
        }

        List<WriteModel<Document>> writes = new ArrayList<>(grouped.size());
        for (Map.Entry<String, NormalizedTxn> entry : grouped.entrySet()) {
            String canonicalId = entry.getKey();
            NormalizedTxn t = entry.getValue();

            Document existingDoc = collection.find(Filters.eq("_id", canonicalId)).first();
            if (existingDoc != null) {
                List<String> existingMsgIds = existingDoc.getList("source_message_ids", String.class);
                if (existingMsgIds != null && !existingMsgIds.isEmpty()) {
                    Set<String> combined = new TreeSet<>(existingMsgIds);
                    combined.addAll(t.sourceMessageIds());
                    if (combined.size() > t.sourceMessageIds().size()) {
                        String merchant = t.merchant().isEmpty() ? existingDoc.getString("merchant") : t.merchant();
                        t = new NormalizedTxn(
                                t.accountLast4(),
                                t.occurredAt(),
                                t.direction(),
                                t.amount(),
                                t.category(),
                                merchant == null ? "" : merchant,
                                new ArrayList<>(combined)
                        );
                    }
                }
            }

            Document doc = toDocument(canonicalId, t);
            writes.add(new ReplaceOneModel<>(
                    Filters.eq("_id", canonicalId),
                    doc,
                    new ReplaceOptions().upsert(true)
            ));
        }
        collection.bulkWrite(writes, new BulkWriteOptions().ordered(false));
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            result.add(toNormalizedTxn(doc));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<StoredDocument> allStored() {
        List<StoredDocument> result = new ArrayList<>();
        for (Document doc : collection.find()) {
            String storageId = doc.getString("_id");
            String canonicalId = doc.getString("canonical_id");
            NormalizedTxn txn = toNormalizedTxn(doc);
            result.add(new StoredDocument(storageId, canonicalId, txn));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public long count() {
        return collection.countDocuments();
    }

    public MongoCollection<Document> getCollection() {
        return collection;
    }

    public ExplainMetrics explainQuery1(String accountLast4, YearMonth month) {
        Bson filter = Filters.and(
                Filters.eq("account_last4", accountLast4),
                Filters.eq("year_month", month.toString())
        );
        Bson sort = Indexes.compoundIndex(
                Indexes.descending("occurred_at_epoch_nanos"),
                Indexes.descending("canonical_id")
        );
        Document explainDoc = collection.find(filter).sort(sort).explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS);
        return parseExplain(explainDoc);
    }

    public ExplainMetrics explainQuery2(String accountLast4) {
        List<Bson> pipeline = List.of(
                Aggregates.match(Filters.eq("account_last4", accountLast4)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount"))
        );
        Document explainDoc = collection.aggregate(pipeline).explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS);
        return parseExplain(explainDoc);
    }

    public ExplainMetrics explainQuery3(String messageId) {
        Bson filter = Filters.eq("source_message_ids", messageId);
        Document explainDoc = collection.find(filter).explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS);
        return parseExplain(explainDoc);
    }

    private ExplainMetrics parseExplain(Document explainDoc) {
        if (explainDoc == null) return new ExplainMetrics(0, 0, 0, "UNKNOWN", "NONE", false);

        Document executionStats = (Document) explainDoc.get("executionStats");
        if (executionStats == null) {
            List<Document> stages = explainDoc.getList("stages", Document.class);
            if (stages != null) {
                for (Document stage : stages) {
                    if (stage.containsKey("$cursor")) {
                        Document cursor = stage.get("$cursor", Document.class);
                        if (cursor != null && cursor.containsKey("executionStats")) {
                            executionStats = cursor.get("executionStats", Document.class);
                            break;
                        }
                    }
                }
            }
        }

        if (executionStats == null) {
            return new ExplainMetrics(0, 0, 0, "UNKNOWN", "NONE", false);
        }

        long totalKeys = executionStats.get("totalKeysExamined") instanceof Number n ? n.longValue() : 0L;
        long totalDocs = executionStats.get("totalDocsExamined") instanceof Number n ? n.longValue() : 0L;
        long nReturned = executionStats.get("nReturned") instanceof Number n ? n.longValue() : 0L;

        Document execStages = executionStats.get("executionStages", Document.class);
        String winningPlan = "COLLSCAN";
        String indexName = "NONE";
        boolean hasSort = false;

        if (execStages != null) {
            hasSort = containsStage(execStages, "SORT");
            Document ixStage = findStage(execStages, "IXSCAN");
            if (ixStage != null) {
                indexName = ixStage.getString("indexName");
                if (indexName == null) indexName = "INDEX_SCAN";
                winningPlan = "IXSCAN (" + indexName + ")";
            } else {
                String topStage = execStages.getString("stage");
                winningPlan = topStage != null ? topStage : "UNKNOWN";
            }
        }

        return new ExplainMetrics(totalKeys, totalDocs, nReturned, winningPlan, indexName, hasSort);
    }

    private boolean containsStage(Document stage, String targetStage) {
        if (stage == null) return false;
        String name = stage.getString("stage");
        if (targetStage.equalsIgnoreCase(name)) return true;

        Document input = stage.get("inputStage", Document.class);
        if (input != null && containsStage(input, targetStage)) return true;

        List<Document> inputs = stage.getList("inputStages", Document.class);
        if (inputs != null) {
            for (Document in : inputs) {
                if (containsStage(in, targetStage)) return true;
            }
        }
        return false;
    }

    private Document findStage(Document stage, String targetStage) {
        if (stage == null) return null;
        String name = stage.getString("stage");
        if (targetStage.equalsIgnoreCase(name)) return stage;

        Document input = stage.get("inputStage", Document.class);
        if (input != null) {
            Document found = findStage(input, targetStage);
            if (found != null) return found;
        }

        List<Document> inputs = stage.getList("inputStages", Document.class);
        if (inputs != null) {
            for (Document in : inputs) {
                Document found = findStage(in, targetStage);
                if (found != null) return found;
            }
        }
        return null;
    }

    public record ExplainMetrics(
            long totalKeysExamined,
            long totalDocsExamined,
            long nReturned,
            String winningPlan,
            String indexName,
            boolean hasSortStage
    ) {}

    private Document toDocument(String canonicalId, NormalizedTxn t) {
        Instant instant = t.occurredAt().toInstant();
        long epochNanos = instant.getEpochSecond() * 1_000_000_000L + instant.getNano();

        ZoneId bankZone = ZoneId.of("Asia/Kolkata");
        String yearMonthStr = YearMonth.from(t.occurredAt().atZoneSameInstant(bankZone)).toString();

        return new Document()
                .append("_id", canonicalId)
                .append("canonical_id", canonicalId)
                .append("account_last4", t.accountLast4())
                .append("year_month", yearMonthStr)
                .append("occurred_at", t.occurredAt().toString())
                .append("occurred_at_epoch_nanos", epochNanos)
                .append("direction", t.direction().name())
                .append("amount", Decimal128.parse(t.amount().toPlainString()))
                .append("category", t.category().name())
                .append("merchant", t.merchant())
                .append("normalized_merchant", MerchantNormalizer.normalize(t.merchant()))
                .append("source_message_ids", t.sourceMessageIds());
    }

    private NormalizedTxn toNormalizedTxn(Document doc) {
        String accountLast4 = doc.getString("account_last4");
        OffsetDateTime occurredAt = OffsetDateTime.parse(doc.getString("occurred_at"));
        Direction direction = Direction.valueOf(doc.getString("direction"));

        Decimal128 amtDec = doc.get("amount", Decimal128.class);
        BigDecimal amount = amtDec.bigDecimalValue().setScale(2, RoundingMode.HALF_UP);

        Category category = Category.valueOf(doc.getString("category"));
        String merchant = doc.getString("merchant");
        List<String> sourceIds = doc.getList("source_message_ids", String.class);

        return new NormalizedTxn(accountLast4, occurredAt, direction, amount, category, merchant, sourceIds);
    }

    @Override
    public void close() {
        client.close();
    }
}
