package com.bcardo.ringcoach

import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun legacyHistoryStateRemainsReachableUnderJournal() {
        assertEquals("journal_archive", restoredDestination("history"))
        assertEquals("journal", primaryDestination("history"))
        assertEquals("journal", parentDestination("history"))
    }
    @Test fun detailsReturnToTheirContextAndOnlyThreePrimaryTabsExist() {
        assertEquals(listOf("home", "training", "journal"), PRIMARY_DESTINATIONS.map { it.first })
        assertEquals("home", primaryDestination("recovery")); assertEquals("home", parentDestination("recovery"))
        assertEquals("journal", primaryDestination("journal_archive")); assertEquals("journal", parentDestination("journal_archive"))
        assertEquals("home", parentDestination("journal")); assertEquals("home", parentDestination("training"))
        assertEquals("home", restoredDestination("obsolete"))
    }
}
