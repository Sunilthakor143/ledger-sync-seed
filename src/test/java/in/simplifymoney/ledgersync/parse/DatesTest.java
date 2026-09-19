package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class DatesTest {

    @Test
    void parsesHdfcV1DateFormat() {
        OffsetDateTime dt = Dates.ist("01-07-26 09:02");
        assertNotNull(dt);
        assertEquals(2026, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(1, dt.getDayOfMonth());
        assertEquals(9, dt.getHour());
        assertEquals(2, dt.getMinute());
        assertEquals(Dates.IST, dt.getOffset());
    }

    @Test
    void parsesHdfcV2DateFormat() {
        OffsetDateTime dt = Dates.ist("23 Jul 26 22:16");
        assertNotNull(dt);
        assertEquals(2026, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(23, dt.getDayOfMonth());
        assertEquals(22, dt.getHour());
        assertEquals(16, dt.getMinute());
    }

    @Test
    void parsesIciciV1DateFormat() {
        OffsetDateTime dt = Dates.ist("01/07/2026 10:22");
        assertNotNull(dt);
        assertEquals(2026, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(1, dt.getDayOfMonth());
        assertEquals(10, dt.getHour());
        assertEquals(22, dt.getMinute());
    }

    @Test
    void parsesIciciV2DateFormat() {
        OffsetDateTime dt = Dates.ist("23-Jul-2026 16:52");
        assertNotNull(dt);
        assertEquals(2026, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(23, dt.getDayOfMonth());
        assertEquals(16, dt.getHour());
        assertEquals(52, dt.getMinute());
    }

    @Test
    void parsesRfc1123EmailDateHeader() {
        OffsetDateTime dt = Dates.parse("Wed, 01 Jul 2026 09:02:00 +0530");
        assertNotNull(dt);
        assertEquals(2026, dt.getYear());
        assertEquals(7, dt.getMonthValue());
        assertEquals(1, dt.getDayOfMonth());
        assertEquals(9, dt.getHour());
        assertEquals(2, dt.getMinute());
        assertEquals(Dates.IST, dt.getOffset());
    }
}
