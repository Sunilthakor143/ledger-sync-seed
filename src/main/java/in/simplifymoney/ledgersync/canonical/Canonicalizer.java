package in.simplifymoney.ledgersync.canonical;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

        // Group evidence by stable physical instant key: (accountLast4, occurredInstant, direction, amount)
        Map<InstantKey, List<ParsedTxn>> instantKeyGroups = new LinkedHashMap<>();
        for (ParsedTxn p : sortedEvidence) {
            InstantKey key = new InstantKey(p.accountLast4(), p.occurredAt().toInstant(), p.direction(), p.amount());
            instantKeyGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<NormalizedTxn> result = new ArrayList<>();

        for (Map.Entry<InstantKey, List<ParsedTxn>> entry : instantKeyGroups.entrySet()) {
            InstantKey ik = entry.getKey();
            List<ParsedTxn> evidenceForInstant = entry.getValue();

            // Find all distinct non-blank normalized merchants in this instant key group
            Map<String, List<ParsedTxn>> knownMerchantGroups = new LinkedHashMap<>();
            List<ParsedTxn> blankEvidenceList = new ArrayList<>();

            for (ParsedTxn p : evidenceForInstant) {
                String norm = MerchantNormalizer.normalize(p.merchant());
                if (!norm.isEmpty()) {
                    knownMerchantGroups.computeIfAbsent(norm, k -> new ArrayList<>()).add(p);
                } else {
                    blankEvidenceList.add(p);
                }
            }

            if (knownMerchantGroups.isEmpty()) {
                // 0 known merchants: group all blank evidence into 1 blank canonical transaction
                StableKey sk = new StableKey(ik.accountLast4, ik.occurredInstant, ik.direction, ik.amount, "");
                result.add(buildCanonicalTxn(sk, "", blankEvidenceList));
            } else if (knownMerchantGroups.size() == 1) {
                // Exactly 1 known merchant: enrich blank evidence into that single known merchant
                String knownNorm = knownMerchantGroups.keySet().iterator().next();
                StableKey sk = new StableKey(ik.accountLast4, ik.occurredInstant, ik.direction, ik.amount, knownNorm);
                List<ParsedTxn> allEvidence = new ArrayList<>(evidenceForInstant);
                result.add(buildCanonicalTxn(sk, knownNorm, allEvidence));
            } else {
                // 2+ distinct known merchants: AMBIGUOUS!
                // Known merchants each form their own canonical transaction.
                for (Map.Entry<String, List<ParsedTxn>> kmEntry : knownMerchantGroups.entrySet()) {
                    String norm = kmEntry.getKey();
                    StableKey sk = new StableKey(ik.accountLast4, ik.occurredInstant, ik.direction, ik.amount, norm);
                    result.add(buildCanonicalTxn(sk, norm, kmEntry.getValue()));
                }
                // Blank evidence forms its own separate blank merchant transaction
                if (!blankEvidenceList.isEmpty()) {
                    StableKey sk = new StableKey(ik.accountLast4, ik.occurredInstant, ik.direction, ik.amount, "");
                    result.add(buildCanonicalTxn(sk, "", blankEvidenceList));
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
        List<String> rawMerchants = new ArrayList<>();

        for (ParsedTxn p : evidence) {
            messageIds.add(p.sourceMessageId());
            if (p.merchant() != null && !p.merchant().isBlank()) {
                rawMerchants.add(p.merchant().trim());
            }
        }

        List<String> sortedIds = new ArrayList<>(messageIds);
        Collections.sort(sortedIds);

        // Sort candidate raw merchants lexicographically for deterministic merchant string resolution
        Collections.sort(rawMerchants);
        String displayMerchant = !rawMerchants.isEmpty() ? rawMerchants.get(0) : normMerchant;

        OffsetDateTime canonicalOccurredAt = key.occurredInstant.atOffset(ZoneOffset.UTC);

        return new NormalizedTxn(key.accountLast4, canonicalOccurredAt, key.direction, key.amount, cat, displayMerchant, sortedIds);
    }

    private record InstantKey(String accountLast4, Instant occurredInstant, Direction direction, BigDecimal amount) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            InstantKey that = (InstantKey) o;
            return Objects.equals(accountLast4, that.accountLast4) &&
                   Objects.equals(occurredInstant, that.occurredInstant) &&
                   direction == that.direction &&
                   (amount != null && that.amount != null && amount.compareTo(that.amount) == 0);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountLast4, occurredInstant, direction, amount != null ? amount.stripTrailingZeros() : null);
        }
    }

    private record StableKey(String accountLast4, Instant occurredInstant, Direction direction, BigDecimal amount, String normalizedMerchant) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StableKey stableKey = (StableKey) o;
            return Objects.equals(accountLast4, stableKey.accountLast4) &&
                   Objects.equals(occurredInstant, stableKey.occurredInstant) &&
                   direction == stableKey.direction &&
                   (amount != null && stableKey.amount != null && amount.compareTo(stableKey.amount) == 0) &&
                   Objects.equals(normalizedMerchant, stableKey.normalizedMerchant);
        }

        @Override
        public int hashCode() {
            return Objects.hash(accountLast4, occurredInstant, direction, amount != null ? amount.stripTrailingZeros() : null, normalizedMerchant);
        }
    }
}
