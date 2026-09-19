package in.simplifymoney.ledgersync.canonical;

/**
 * Normalizes merchant names for canonical transaction matching.
 */
public final class MerchantNormalizer {

    private MerchantNormalizer() {}

    /**
     * Normalizes raw merchant strings:
     * - Null or blank -> ""
     * - Trim leading and trailing whitespace
     * - Collapse multiple consecutive whitespace characters (\s+) to a single space
     * - Convert to UPPERCASE
     */
    public static String normalize(String rawMerchant) {
        if (rawMerchant == null || rawMerchant.isBlank()) {
            return "";
        }
        return rawMerchant.trim().replaceAll("\\s+", " ").toUpperCase();
    }
}
