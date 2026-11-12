package com.bcardo.ringcoach

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class NightlyDatesTest {
    @Test fun nullOffsetCalendarDayDoesNotShiftToPreviousNightInEasternTime() {
        assertEquals(LocalDate.of(2026, 11, 11), NightlyDates.energyDate(Instant.parse("2026-11-11T00:00:00Z"), null))
        assertEquals(LocalDate.of(2026, 11, 11), NightlyDates.energyDate(Instant.parse("2026-11-11T05:00:00Z"), ZoneOffset.ofHours(-5)))
    }
}
