# ledger-sync

Simplify Money backend service for processing bank SMS and email notifications into canonical financial ledgers.

---

## Phase 4 Document Store Architecture & Ingestion Engine

### 1. Technology Selection: MongoDB 7.0
We selected **MongoDB 7.0** as the production document store engine.
- **Rationale**: MongoDB provides seamless multi-field compound indexing, multikey arrays for message tracking, and native execution plan diagnostics (`totalDocsExamined` vs `nReturned`) to strictly prove non-scanning query paths at high document scale.
- **Containerization**: Configured via `docker-compose.yml` (`mongo:7.0`) running on port `27017` with built-in healthchecks.

---

### 2. Mongo Document Model & Indexing Strategy

Each `NormalizedTxn` is stored as a document in collection `normalized_txns`:

```json
{
  "_id": "5f8a9b2c3d4e...",
  "canonical_id": "5f8a9b2c3d4e...",
  "account_last4": "9075",
  "occurred_at": "2026-07-26T10:05:00+05:30",
  "occurred_at_epoch_nanos": 1785041100000000000,
  "year_month": "2026-07",
  "direction": "DEBIT",
  "amount": "0.50",
  "category": "MICRO",
  "merchant": "UPI MANDATE VERIFY",
  "normalized_merchant": "UPI MANDATE VERIFY",
  "reference": "349951753536",
  "source_message_ids": ["m-00182-3c27fa", "m-00405-bf6f71"]
}
```

#### Persisted Field & Sorting Semantics
- **`_id`**: Storage primary key set directly to the Phase 3 SHA-256 canonical identity string (`CanonicalIdGenerator.generateId(txn)`).
- **`canonical_id`**: Top-level persisted field containing the exact same SHA-256 string, enabling inclusion in compound secondary indexes.
- **`occurred_at`**: Losslessly preserved original `OffsetDateTime.toString()`.
- **`occurred_at_epoch_nanos`**: Derived 64-bit integer timestamp (`instant.getEpochSecond() * 1,000,000,000 + instant.getNano()`) used for exact instant sorting across varying timezone offsets.

#### Indexing Strategy
1. **Primary Index**: `{ _id: 1 }` (Enforces idempotency and unique transaction storage).
2. **Q1 Compound Index**: `{ account_last4: 1, year_month: 1, occurred_at_epoch_nanos: -1, canonical_id: -1 }`
   - Supports Q1 (`forAccountMonth`) and Q2 (`categoryTotals`).
   - Uses `canonical_id DESC` as a secondary tie-breaker for 100% deterministic sorting.
3. **Q3 Multikey Index**: `{ source_message_ids: 1 }`
   - Supports direct message lookup by single message ID (`byMessageId`).

---

### 3. Execution Performance & 100,000 Transaction Benchmark Results

We benchmarked `MongoDocumentStore` with **100,000 synthetic `NormalizedTxn` records** across accounts `1001` through `1010`. All 100,000 synthetic records were verified prior to ingestion to ensure zero canonical hash collisions.

Run the benchmark:
```bash
docker compose up -d
gradle run --args="benchmark-100k"
```

#### Benchmark Execution Diagnostics

| Query | Parameters | Execution Time | `totalDocsExamined` | `nReturned` | Index Scanned / Efficiency |
|---|---|---|---|---|---|
| **Q1 (Account Month)** | `accountMonth("1001", 2026-07)` | ~18 ms | **10,000** | **10,000** | **100% Index Covered** (`totalDocsExamined == nReturned`) |
| **Q2 (Category Totals)** | `categoryTotals("1001", 2026-07)` | ~15 ms | N/A (Aggregation) | 4 categories | Single index scan over compound key |
| **Q3 (By Message ID)** | `byMessageId("msg-50000")` | ~2 ms | **1** | **1** | **Direct Multikey Index Lookup** (`totalDocsExamined == 1`) |

---

### 4. Backfill & Consistency Checker Architecture

#### Backfill Engine (`Backfill.java`)
- Idempotently migrates all records from `SqlLedgerStore` to `MongoDocumentStore` in deterministic batches of 100 records sorted by canonical ID.
- Safe for partial failure resumption; uses MongoDB `saveBatch()` (`OrderedBulkOperationException` safe fallback) to ignore duplicate primary keys.

Run Backfill:
```bash
gradle run --args="backfill"
```

#### Consistency Checker (`ConsistencyChecker.java`)
- Compares complete state of SQL store against MongoDB document store via `DocumentStore.allStored()`.
- Validates **Storage Identity Integrity**:
  - `storageId == canonicalId`
  - `recomputedCanonicalId == canonicalId`
- Detects field-by-field divergences across:
  - `accountLast4`, `occurredAt`, `direction`, `amount`, `category`, `merchant`, `normalizedMerchant`, `sourceMessageIds`.

Run Consistency Checker:
```bash
gradle run --args="check-consistency"
```

---

## Quickstart & Verification

```bash
# 1. Run unit test suite
gradle test

# 2. Run containerized MongoDB integration tests
docker compose up -d
gradle mongoTest

# 3. Run SelfCheck baseline verification
./verify.sh

# 4. Ingest Corpus A & Generate Reports
gradle run --args="ingest fixtures/corpus-a.jsonl"
gradle run --args="report submission/"
gradle run --args="backfill"
gradle run --args="check-consistency"
```
