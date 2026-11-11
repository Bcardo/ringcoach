package com.bcardo.ringcoach

import java.time.Instant
import java.time.LocalDate

internal data class SavedCoachCard(
    val id: String,
    val date: LocalDate,
    val createdAt: Instant,
    val model: String,
    val promptVersion: String,
    val contextJson: String,
    val advice: CoachAdvice,
)
