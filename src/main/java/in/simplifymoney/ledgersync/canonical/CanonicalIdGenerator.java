package in.simplifymoney.ledgersync.canonical;

import in.simplifymoney.ledgersync.model.Direction;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;

/**
 * Computes deterministic SHA-256 canonical identity hashes for transactions.
 */
public final class CanonicalIdGenerator {

    private CanonicalIdGenerator() {}

    public static String generateId(
            String accountLast4,
            OffsetDateTime occurredAt,
            Direction direction,
            BigDecimal amount,
            String rawMerchant) {
        String normalizedMerchant = MerchantNormalizer.normalize(rawMerchant);
        String rawKey = String.join("|",
                accountLast4 != null ? accountLast4.trim() : "",
                occurredAt != null ? occurredAt.toString() : "",
                amount != null ? amount.toPlainString() : "",
                direction != null ? direction.name() : "",
                normalizedMerchant);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    public static String generateId(in.simplifymoney.ledgersync.model.NormalizedTxn txn) {
        if (txn == null) return null;
        return generateId(txn.accountLast4(), txn.occurredAt(), txn.direction(), txn.amount(), txn.merchant());
    }
}
