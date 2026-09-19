package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.canonical.MerchantNormalizer;
import org.junit.jupiter.api.Test;

public class MerchantNormalizerTest {

    @Test
    void handlesNullAndBlank() {
        assertEquals("", MerchantNormalizer.normalize(null));
        assertEquals("", MerchantNormalizer.normalize(""));
        assertEquals("", MerchantNormalizer.normalize("   "));
    }

    @Test
    void trimsAndCollapsesSpaces() {
        assertEquals("SWIGGY", MerchantNormalizer.normalize("  swiggy  "));
        assertEquals("UPI / BARBER", MerchantNormalizer.normalize("  upi  /   barber "));
    }

    @Test
    void convertsToUppercase() {
        assertEquals("RELIANCE SMART", MerchantNormalizer.normalize("Reliance Smart"));
    }

    @Test
    void preservesPunctuation() {
        assertEquals("UPI/P2P/REFUND", MerchantNormalizer.normalize("upi/p2p/refund"));
        assertEquals("AMAZON.IN", MerchantNormalizer.normalize("Amazon.in"));
        assertEquals("M-PESA", MerchantNormalizer.normalize("m-pesa"));
        assertEquals("AT&T", MerchantNormalizer.normalize("at&t"));
        assertEquals("USER@MERCHANT", MerchantNormalizer.normalize("user@merchant"));
    }
}
