package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernStatisticsDateRangeTest {
    @Test
    void berlinSpringDstPresetUsesSevenCalendarDays() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        long now = LocalDateTime.of(2026, 3, 30, 12, 0).atZone(berlin).toInstant().toEpochMilli();
        long start = ModernStatisticsScreen.rangeStartForPreset(now, 7, berlin);

        assertEquals(LocalDateTime.of(2026, 3, 24, 0, 0), java.time.Instant.ofEpochMilli(start).atZone(berlin).toLocalDateTime());
        assertEquals(Duration.ofHours(155), Duration.between(java.time.Instant.ofEpochMilli(start), java.time.Instant.ofEpochMilli(now)));
    }

    @Test
    void berlinAutumnDstPresetUsesSevenCalendarDays() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        long now = LocalDateTime.of(2026, 10, 26, 12, 0).atZone(berlin).toInstant().toEpochMilli();
        long start = ModernStatisticsScreen.rangeStartForPreset(now, 7, berlin);

        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0), java.time.Instant.ofEpochMilli(start).atZone(berlin).toLocalDateTime());
        assertEquals(Duration.ofHours(157), Duration.between(java.time.Instant.ofEpochMilli(start), java.time.Instant.ofEpochMilli(now)));
    }
}
