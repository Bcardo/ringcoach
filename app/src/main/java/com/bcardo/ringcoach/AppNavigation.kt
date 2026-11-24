package com.bcardo.ringcoach

internal val PRIMARY_DESTINATIONS = listOf("home" to "今日", "training" to "训练", "journal" to "日志")

internal fun restoredDestination(value: String): String = when (value) {
    "history" -> "journal_archive"
    "home", "training", "journal", "journal_archive", "recovery" -> value
    else -> "home"
}

internal fun primaryDestination(value: String): String = when (restoredDestination(value)) {
    "journal_archive" -> "journal"
    "recovery" -> "home"
    else -> restoredDestination(value)
}

internal fun parentDestination(value: String): String = when (restoredDestination(value)) {
    "journal_archive" -> "journal"
    else -> "home"
}
