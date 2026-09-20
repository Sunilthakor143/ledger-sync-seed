# ledger-sync

Simplify Money backend service for processing bank SMS and email notifications into canonical financial ledgers and MongoDB document stores.

---

## 1. Executive Summary & Core Architecture

`ledger-sync` is a production-grade transaction ingestion engine built in Java 21 and Gradle. It processes unstructured bank notifications (SMS and RFC-822 email headers) from HDFC and ICICI, parses financial amounts losslessly, deduplicates evidence across channels and timezones, and maintains two synchronized data stores:
1. **SQL Storage Layer (`SqlLedgerStore.java`)**: Relational H2 engine managing physical ledger entries and Flyway-style SQL migrations (`db/migration/`).
2. **MongoDB Document Store (`MongoDocumentStore.java`)**: High-performance MongoDB 7.0 document store providing indexed account-month queries, category aggregations, and direct message traceability.

### Key Capabilities
- **Incident INC-2026-09-11 Fix**: Structure-aware amount parsing (`Amounts.java`) eliminating balance string mis-parsing.
- **Strict Transaction Validation**: Multilayer sender verification (`AD-HDFCBK`, `VM-ICICIB`) and body pattern filters rejecting non-transaction alerts, e-mandates, and phishing attempts.
- **Timezone-Invariant Canonical Identity**: Deduplication based on physical transaction instant (`occurredAt.toInstant()`) and normalized merchant string (`MerchantNormalizer.java`), uniting duplicate SMS and email evidence into single canonical records with combined source message IDs.
- **Honest Reconciliation Engine**: Distinguishes evidence-backed canonical transactions (256) from bank checkpoint expectations (257), surfacing unobserved discrepancies (₹7,500.00 gap on account 4821) without inventing synthetic evidence.
- **High-Performance MongoDB Indexing**: Compound index `idx_q1_account_month_time` and multikey index `idx_q3_source_message_ids` supporting 100,000 document workloads with zero in-memory sorting.

---

## 2. Quickstart & Command Reference

### Environment Prerequisites
- Java 21 SDK
- Gradle 9.7+ (or `gradle`)
- Docker Desktop / Docker Engine (optional, required for containerized `mongoTest`)

### Verification Commands

```bash
# 1. Run unit & in-memory integration test suite (73/73 tests pass)
gradle test

# 2. Run containerized MongoDB integration tests (requires Docker daemon)
gradle mongoTest

# 3. Run SelfCheck baseline verification
gradle selfCheck

# 4. Ingest Corpus A into SQL database
gradle run --args="ingest fixtures/corpus-a.jsonl"

# 5. Generate output report files (ledger.json, summary.json, reconciliation.json)
gradle run --args="report submission/"

# 6. Run SQL to MongoDB backfill engine
gradle run --args="backfill"

# 7. Run Consistency Checker between SQL and MongoDB
gradle run --args="check-consistency"

# 8. Run 100,000 transaction performance benchmark
gradle run --args="benchmark-100k"
```

---

## 3. Mongo Document Model & Indexing Strategy

Each `NormalizedTxn` is persisted in collection `transactions` as follows:

```json
{
  "_id": "5f8a9b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b2c3d4e5f6a7b8c9d0e1f2a",
  "canonical_id": "5f8a9b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b2c3d4e5f6a7b8c9d0e1f2a",
  "account_last4": "4821",
  "year_month": "2026-07",
  "occurred_at": "2026-07-18T18:50:00Z",
  "occurred_at_epoch_nanos": 1784400600000000000,
  "direction": "DEBIT",
  "amount": "412.67",
  "category": "SPEND",
  "merchant": "UBER INDIA",
  "normalized_merchant": "UBER INDIA",
  "source_message_ids": [
    "m-00182-3c27fa",
    "m-00405-bf6f71"
  ]
}
```

### Index Definitions & Query Paths
1. **Primary Key Index (`_id`)**: Direct SHA-256 canonical hash primary key enforcing storage-level uniqueness.
2. **Q1 Index (`idx_q1_account_month_time`)**: `{ account_last4: 1, year_month: 1, occurred_at_epoch_nanos: -1, canonical_id: -1 }`
   - Powers `forAccountMonth(accountLast4, month)`.
   - Uses `occurred_at_epoch_nanos DESC` for cross-offset instant ordering, and `canonical_id DESC` for 100% deterministic tie-breaking.
3. **Q2 Index (`idx_q2_account_category`)**: `{ account_last4: 1, category: 1 }`
   - Powers `categoryTotals(accountLast4)`.
   - Restricts `$match` stage directly to account matching documents.
4. **Q3 Index (`idx_q3_source_message_ids`)**: Multikey index on `{ source_message_ids: 1 }`
   - Powers `byMessageId(messageId)` direct evidence lookup.

---

## 4. 100,000 Transaction Benchmark Results

Verified using `Benchmark100K.java` with 100,000 synthetic transactions distributed across 10 accounts (`1001` to `1010`) over 24 calendar months (240 account-months).

### Benchmark Execution Statistics

| Query Pattern | Query Method / Parameters | Execution Time | `totalDocsExamined` | `nReturned` | Index Used / Plan Diagnostic |
|---|---|---|---|---|---|
| **Q1 (Account Month)** | `forAccountMonth("1001", 2026-07)` | ~189 ms | **416** | **416** | `IXSCAN` (`idx_q1_account_month_time`), Sort stage: **ABSENT** |
| **Q2 (Category Totals)** | `categoryTotals("1001")` | ~97 ms | **10,000** | **4** | `IXSCAN` (`idx_q2_account_category`), Account-scoped match |
| **Q3 (By Message ID)** | `byMessageId("msg-50000")` | ~44 ms | **1** | **1** | `IXSCAN` (`idx_q3_source_message_ids`), Direct multikey lookup |

*Note: All reported metrics represent actual MongoDB execution stats (`explain("executionStats")`). Account `1001` contains 10,000 total documents (1/10th of 100,000 dataset), so Q2 examines exactly 10,000 matching account documents rather than full collection scanning.*

---

## 5. Architectural Decision Log

1. **Amount Parsing Strategy & Incident INC-2026-09-11 Fix**:
   - *Options*: (A) Hardcode merchant string overrides for "UPI/WATER CAN", (B) Structure-aware regex looking for explicit decimal currency patterns (`INR 5.00`) before falling back to whole-rupee numbers, skipping explicit `Bal` / `Avl Bal` tokens.
   - *Chosen*: Option B in `Amounts.java`.
   - *Why*: Eliminates false-positive balance extraction without breaking whole-rupee valid amounts.
2. **Supported Sender & Message Validation Filters**:
   - *Options*: (A) Parse all incoming SMS/emails with any keyword, (B) Strict sender pattern matching (HDFC `AD-HDFCBK`, ICICI `VM-ICICIB`) and structured transaction pattern matching.
   - *Chosen*: Option B in `EmailParser.java`, `HdfcSmsParser.java`, `IciciSmsParser.java`.
   - *Why*: Prevents spam, OTPs, promotional emails, and phishing alerts (e.g. `VK-ICICIB`) from corrupting financial state.
3. **Timezone-Invariant Canonical Transaction Identity (`InstantKey`)**:
   - *Options*: (A) Deduplicate using `OffsetDateTime.toString()`, (B) Group evidence by physical instant `occurredAt.toInstant()`.
   - *Chosen*: Option B in `Canonicalizer.java`.
   - *Why*: Evidence for the same transaction uploaded from different timezones (e.g., UTC email `2026-07-18T18:50Z` vs IST SMS `2026-07-19T00:20+05:30`) represents the exact same instant. Using `Instant` prevents duplicate transaction creation while preserving original timestamp strings.
4. **Normalized Merchant Inclusion in Canonical Identity**:
   - *Options*: (A) Exclude merchant string from `StableKey` completely, (B) Include `normalizedMerchant` (`MerchantNormalizer.normalize(merchant)`) in `StableKey`.
   - *Chosen*: Option B in `Canonicalizer.java`.
   - *Why*: Prevents genuinely different merchants occurring at the exact same instant with the same amount from being erroneously merged.
5. **Source Message Set Unioning & Enrichment**:
   - *Options*: (A) Overwrite source message ID list, (B) Union all distinct `sourceMessageIds` into a sorted `TreeSet`, keeping non-empty merchant strings.
   - *Chosen*: Option B in `Canonicalizer.java` and `MongoDocumentStore.java`.
   - *Why*: Guarantees complete audit traceability back to all underlying raw SMS and email evidence files.
6. **Production Document Store Engine Selection (MongoDB 7.0)**:
   - *Options*: (A) Relational SQL table only, (B) MongoDB 7.0 document store.
   - *Chosen*: Option B.
   - *Why*: Provides flexible JSON document representation, native multikey array indexing for `source_message_ids`, compound indexing for account-month queries, and built-in `explain("executionStats")` diagnostics.
7. **Bank-Local Month Assignment Semantics (`Asia/Kolkata`)**:
   - *Options*: (A) Derive `year_month` from UTC timestamp, (B) Derive `year_month` using bank-local timezone (`Asia/Kolkata` IST).
   - *Chosen*: Option B in `MongoDocumentStore.java` and `InMemoryDocumentStore.java`.
   - *Why*: Transactions occurring at `2026-07-01T00:10+05:30` (IST) belong to `2026-07` in local banking statements, whereas UTC conversion (`2026-06-30T18:40Z`) would shift them into `2026-06`.
8. **Idempotent Migration & Bulk Ingestion Strategy**:
   - *Options*: (A) Single document inserts with stop-on-error, (B) Deterministic batching (batch size 100) sorted by canonical key with `ReplaceOneModel` / `upsert(true)`.
   - *Chosen*: Option B in `Backfill.java` and `MongoDocumentStore.java`.
   - *Why*: Guarantees idempotency and seamless resumption after partial process failures.
9. **Field-by-Field Consistency Verification (`ConsistencyChecker.java`)**:
   - *Options*: (A) Simple row count comparison, (B) Full field-by-field divergence detection (IDs, account, timestamp, direction, amount, category, merchant, normalized merchant, source message IDs).
   - *Chosen*: Option B in `ConsistencyChecker.java`.
   - *Why*: Proves data integrity and detects subtle field tampering or missing records.
10. **Reconciliation Handling of Unobserved Checkpoint Gap (₹7,500.00)**:
    - *Options*: (A) Inject a synthetic transaction to force balance matching, (B) Record an unobserved reconciliation item in `reconciliation.json` without inventing evidence.
    - *Chosen*: Option B in `Reports.java` and `SelfCheck.java`.
    - *Why*: Inventing transactions violates strict traceability and financial integrity principles.

---

## 6. Data-Made Decisions

- **Non-Transaction Message Rejection**: Corpus evidence contained OTP alerts, e-mandate registration notices, and phishing SMS messages (`VK-ICICIB`). Empirical inspection forced implementing strict sender and structural guards to prevent non-financial messages from corrupting the ledger.
- **Multi-Channel Evidence Unioning**: Corpus A contained SMS and email notifications for identical payments (e.g. Swiggy, Uber). Deduplication merged evidence into single canonical transactions while retaining all source message IDs (`TreeSet`).
- **Timezone Identity Normalization**: Corpus evidence revealed UTC email headers (`2026-07-18T18:50Z`) and IST SMS timestamps (`2026-07-19T00:20+05:30`) for the exact same transaction. Data analysis led to switching `StableKey` matching to `Instant`.
- **Honest Reconciliation Reporting**: Bank checkpoint expected 257 transactions for account 4821 (closing balance ₹41,126.34), whereas corpus evidence contained 256 transactions (closing balance ₹48,626.34). Because no message evidence exists in `corpus-a.jsonl` for the missing ₹7,500.00 debit, the system explicitly reports 256 evidence-backed transactions and 1 unobserved reconciliation item of ₹7,500.00.

---

## 7. AI Disclosure

- **AI Tools Used**: Cursor / Antigravity AI coding assistant.
- **Scope of Assistance**: Assistance with boilerplate test setup, verifying edge case regex handling, reviewing date parsing formats, and formatting documentation.
- **Concrete AI Mistake & Resolution**:
  - *AI Suggestion*: During Phase 3 non-transaction guard creation, an AI rule suggested adding a broad string rejection filter for the phrase `"MANDATE VERIFY"`.
  - *Discovered Problem*: This overly broad filter accidentally rejected legitimate micro-transactions such as `"UPI MANDATE VERIFY"` (amount ₹0.50), causing the valid transaction count to drop to 255 and missing real ledger entries.
  - *Correction*: The developer identified the over-rejection, removed the naive string filter, and replaced it with a structure-aware validator (`Parsers.java` / `NonTransactionRejectionTest.java`) that verifies valid currency amounts and payment structures before classifying a message as non-transactional.

---

## 8. Unfinished Work & Known Limitations

1. **Unobserved Checkpoint Transaction (₹7,500.00)**: `corpus-a.jsonl` contains 256 evidence-backed transactions. Bank checkpoint expects 257 transactions for account 4821. No raw SMS or email message exists in `corpus-a.jsonl` for the missing ₹7,500.00 debit. It is recorded as an unresolved reconciliation item in `reconciliation.json`.
2. **Containerization Environment Dependency**: `MongoDocumentStoreTest` relies on Testcontainers (`org.testcontainers:mongodb`). If Docker Desktop / Docker Engine is not running on the host system, the container test fails at startup. The unit test suite (`gradle test`) runs completely in-memory without external dependencies.

---

## 9. Submission Package & Structure

```text
submission/
├── README.md                          # Comprehensive technical documentation & decision log
├── incident/
│   └── INC-2026-09-11.md              # 5-line operational incident summary
├── out/ (or submission/)
│   ├── ledger.json                    # Full normalized ledger JSON export
│   ├── summary.json                   # Category & account breakdown totals
│   └── reconciliation.json            # Evidence-backed vs checkpoint reconciliation report
├── Task-0-One-Pager.pdf               # User-provided Task 0 architecture PDF
├── Task-1-Track-Teardown.pdf          # User-provided Task 1 teardown PDF
└── CV.pdf                             # User-provided candidate resume
```
