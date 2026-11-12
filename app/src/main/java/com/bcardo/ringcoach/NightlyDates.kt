package com.bcardo.ringcoach

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal object NightlyDates {
    // Energy Score is a calendar-day type. This Fold7 returns UTC-midnight dates with a null offset.
    fun energyDate(start: Instant, offset: ZoneOffset?): LocalDate = start.atOffset(offset ?: ZoneOffset.UTC).toLocalDate()
}
