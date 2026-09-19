package in.simplifymoney.ledgersync.canonical;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Main canonicalization engine for linking evidence into canonical transactions.
 */
public final class Canonicalizer {

    public List<NormalizedTxn> canonicalize(Collection<ParsedTxn> evidenceList) {
        if (evidenceList == null || evidenceList.isEmpty()) {
            return List.of();
        }

        // Sort incoming evidence deterministically by sourceMessageId
        List<ParsedTxn> sortedEvidence = new ArrayList<>(evidenceList);
        sortedEvidence.sort(Comparator.comparing(ParsedTxn::sourceMessageId));

        // Group evidence by stable physical key: (accountLast4, occurredAt, direction, amount)
        Map<StableKey, List<ParsedTxn>> stableKeyGroups = new LinkedHashMap<>();
        for (ParsedTxn p : sortedEvidence) {
            StableKey key = new StableKey(p.accountLast4(), p.occurredAt(), p.direction(), p.amount());
            stableKeyGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<NormalizedTxn> result = new ArrayList<>();

        for (Map.Entry<StableKey, List<ParsedTxn>> entry : stableKeyGroups.entrySet()) {
            StableKey key = entry.getKey();
            List<ParsedTxn> evidenceForTxn = entry.getValue();

            // Find all distinct non-blank normalized merchants in this stable key group
            Map<String, List<ParsedTxn>> knownMerchantGroups = new LinkedHashMap<>();
            List<ParsedTxn> blankEvidenceList = new ArrayList<>();

            for (ParsedTxn p : evidenceForTxn) {
                String norm = MerchantNormalizer.normalize(p.merchant());
                if (!norm.isEmpty()) {
                    knownMerchantGroups.computeIfAbsent(norm, k -> new ArrayList<>()).add(p);
                } else {
                    blankEvidenceList.add(p);
                }
            }

            if (knownMerchantGroups.isEmpty()) {
                // 0 known merchants: group all blank evidence into 1 blank canonical transaction
                result.add(buildCanonicalTxn(key, "", blankEvidenceList));
            } else if (knownMerchantGroups.size() == 1) {
                // Exactly 1 known merchant: enrich blank evidence into that single known merchant
                String knownNorm = knownMerchantGroups.keySet().iterator().next();
                List<ParsedTxn> allEvidence = new ArrayList<>(evidenceForTxn);
                result.add(buildCanonicalTxn(key, knownNorm, allEvidence));
            } else {
                // 2+ distinct known merchants: AMBIGUOUS!
                // Known merchants each form their own canonical transaction.
                for (Map.Entry<String, List<ParsedTxn>> kmEntry : knownMerchantGroups.entrySet()) {
                    result.add(buildCanonicalTxn(key, kmEntry.getKey(), kmEntry.getValue()));
                }
                // Blank evidence forms its own separate blank merchant transaction
                if (!blankEvidenceList.isEmpty()) {
                    result.add(buildCanonicalTxn(key, "", blankEvidenceList));
                }
            }
        }

        // Sort final canonical transactions deterministically
        result.sort(Comparator
                .comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4)
                .thenComparing(NormalizedTxn::amount)
                .thenComparing(NormalizedTxn::merchant)
                .thenComparing(t -> String.join(",", t.sourceMessageIds())));

        return Collections.unmodifiableList(result);
    }

    private NormalizedTxn buildCanonicalTxn(StableKey key, String normMerchant, List<ParsedTxn> evidence) {
        Category cat = key.direction == Direction.DEBIT ? Category.SPEND : Category.INCOME;

        Set<String> messageIds = new HashSet<>();
        String rawMerchant = null;

        for (ParsedTxn p : evidence) {
            messageIds.add(p.sourceMessageId());
            if (rawMerchant == null && p.merchant() != null && !p.merchant().isBlank()) {
                rawMerchant = p.merchant().trim();
            }
        }

        List<String> sortedIds = new ArrayList<>(messageIds);
        Collections.sort(sortedIds);

        String displayMerchant = (rawMerchant != null && !rawMerchant.isBlank())
                ? rawMerchant : normMerchant;

        return new NormalizedTxn(key.accountLast4, key.occurredAt, key.direction, key.amount, cat, displayMerchant, sortedIds);
    }

    private record StableKey(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StableKey stableKey = (StableKey) o;
            return Objects.equals(accountLast4, stableKey.accountLast4) &&
                   Objects.equals(occurredAt, stableKey.occurredAt) &&
                   direction == stableKey.direction &&
                   (amount != null && stableKey.amount != null && amount.compareTo(stableKey.amount) == 0);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountLast4, occurredAt, direction, amount != null ? amount.stripTrailingZeros() : null);
        }
    }
}
