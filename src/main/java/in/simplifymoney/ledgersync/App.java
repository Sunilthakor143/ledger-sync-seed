package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.benchmark.Benchmark100K;
import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 *   backfill                 migrate SQL transactions to MongoDB document store
 *   check-consistency        compare SQL ledger against MongoDB document store
 *   benchmark-100k           run 100,000 synthetic transaction benchmark
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | check-consistency | benchmark-100k");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        String mongoUri = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
        String mongoDbName = System.getenv().getOrDefault("MONGO_DB", "ledger_sync_db");

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                try (SqlLedgerStore sqlStore = new SqlLedgerStore(DB);
                     MongoDocumentStore mongoStore = new MongoDocumentStore(mongoUri, mongoDbName)) {
                    sqlStore.migrate(MIGRATIONS);
                    mongoStore.getCollection().drop();
                    System.out.println("Starting SQL -> MongoDB backfill...");
                    Backfill.Result res = new Backfill(sqlStore, mongoStore).run();
                    long mongoCount = mongoStore.count();
                    System.out.printf("SQL physical rows before deduplication: %d%n", res.read());
                    System.out.printf("SQL canonical transactions: %d%n", mongoCount);
                    System.out.printf("MongoDB documents after backfill: %d%n", mongoCount);
                    System.out.println("Backfill complete.");
                }
            }
            case "check-consistency" -> {
                try (SqlLedgerStore sqlStore = new SqlLedgerStore(DB);
                     MongoDocumentStore mongoStore = new MongoDocumentStore(mongoUri, mongoDbName)) {
                    sqlStore.migrate(MIGRATIONS);
                    System.out.println("Running consistency checker between SQL and MongoDB...");
                    ConsistencyChecker checker = new ConsistencyChecker(sqlStore, mongoStore);
                    List<ConsistencyChecker.Divergence> divergences = checker.check();
                    if (divergences.isEmpty()) {
                        System.out.println("Consistency Check PASSED: SQL and MongoDB document store are perfectly consistent (0 divergences).");
                    } else {
                        System.err.println("Consistency Check FAILED: Found " + divergences.size() + " divergence(s):");
                        for (ConsistencyChecker.Divergence d : divergences) {
                            System.err.printf("  - [%s] SQL: %s | Docs: %s%n", d.what(), d.inSql(), d.inDocuments());
                        }
                        System.exit(1);
                    }
                }
            }
            case "benchmark-100k" -> {
                try (MongoDocumentStore mongoStore = new MongoDocumentStore(mongoUri, mongoDbName + "_bench")) {
                    System.out.println("Running 100K transaction benchmark on MongoDB database: " + mongoDbName + "_bench");
                    Benchmark100K.runBenchmark(mongoStore);
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
