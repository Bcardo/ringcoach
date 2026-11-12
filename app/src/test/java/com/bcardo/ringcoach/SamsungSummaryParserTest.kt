package com.bcardo.ringcoach

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SamsungSummaryParserTest {
    @Test fun readsVendorDailyValuesWithoutUsingChartTicksOrHeartRate() {
        assertEquals(82.0, SamsungSummaryParser.energy("能量得分82减号2良好")!!, 0.0)
        assertEquals(62.0, SamsungSummaryParser.hrv("平均：62 毫秒")!!, 0.0)
        assertNull(SamsungSummaryParser.hrv("平均：63 次/分"))
        assertNull(SamsungSummaryParser.hrv("115"))
        assertNull(SamsungSummaryParser.energy("82"))
        assertNull(SamsungSummaryParser.energy("能量得分182"))
        assertNull(SamsungSummaryParser.hrv("平均：0 毫秒"))
    }
    @Test fun validatesWakeDateAcrossYearBoundaryAndRejectsAmbiguousLabels() {
        val today = LocalDate.of(2026, 1, 1)
        assertTrue(SamsungSummaryParser.matchesDate("今天", today, today))
        assertFalse(SamsungSummaryParser.matchesDate("今天", today.minusDays(1), today))
        assertTrue(SamsungSummaryParser.matchesDate("2025年12月31日周三", today.minusDays(1), today))
        assertFalse(SamsungSummaryParser.matchesDate("2026年12月31日周三", today.minusDays(1), today))
        assertFalse(SamsungSummaryParser.matchesDate("31", today.minusDays(1), today))
    }
}
