package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

/**
 * Exposes a stored document along with both its raw storage ID (_id) and persisted canonical ID.
 *
 * SQL semantics:
 *   - storageId = computed canonical ID
 *   - canonicalId = computed canonical ID
 *   - txn = source NormalizedTxn
 *
 * Mongo semantics:
 *   - storageId = raw Mongo _id
 *   - canonicalId = raw Mongo canonical_id field
 *   - txn = reconstructed NormalizedTxn
 */
public record StoredDocument(String storageId, String canonicalId, NormalizedTxn txn) {}
