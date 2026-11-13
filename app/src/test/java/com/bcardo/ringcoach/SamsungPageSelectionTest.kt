package com.bcardo.ringcoach

import org.junit.Assert.*
import org.junit.Test

class SamsungPageSelectionTest {
    private val viewport = ScreenBox(0, 0, 1800, 2200)
    @Test fun captureCompletionDoesNotReopenRingCoachOverAnotherApp() {
        assertTrue(SamsungPageSelection.canReturnToApp(SAMSUNG_HEALTH_PACKAGE))
        assertFalse(SamsungPageSelection.canReturnToApp("another.app"))
        assertFalse(SamsungPageSelection.canReturnToApp("com.bcardo.ringcoach"))
        assertFalse(SamsungPageSelection.canReturnToApp(null))
    }
    @Test fun missingDataOnAnUnrelatedTrackerCannotEraseHrv() {
        assertEquals(SamsungPageSelection.Route.UNKNOWN, SamsungPageSelection.route(setOf("switcher_period", "no_data_text")))
        assertEquals(SamsungPageSelection.Route.ENERGY, SamsungPageSelection.route(setOf("vitality_main_view", "no_data_text")))
    }
    @Test fun bodyCompositionDateControlsAreNotAnEnergyScreen() {
        assertEquals(SamsungPageSelection.Route.BACK_TO_HOME, SamsungPageSelection.route(setOf("bia_root_view", "switcher_period", "text_button", "date_left_btn")))
    }
    @Test fun homeEnergyTileIsOpenedEvenWhenHomeHasADateControl() {
        assertEquals(SamsungPageSelection.Route.OPEN_ENERGY, SamsungPageSelection.route(setOf("vitality_tile_score_text", "switcher_period")))
        assertEquals(SamsungPageSelection.Route.ENERGY, SamsungPageSelection.route(setOf("contributor_trend_layout", "switcher_period")))
        assertEquals(SamsungPageSelection.Route.OPEN_ENERGY, SamsungPageSelection.route(setOf("new_me_recycler_view", "text_button")))
    }
    @Test fun fullSizeOffscreenPagerNeighborsDoNotCreateAmbiguity() {
        assertEquals(1, SamsungPageSelection.select(viewport, listOf(
            ScreenBox(-1800, 100, 0, 2100) to true,
            ScreenBox(0, 100, 1800, 2100) to true,
            ScreenBox(1800, 100, 3600, 2100) to true)))
    }
    @Test fun tiesDuringSwipeAreNotAssignedToEitherDate() {
        assertNull(SamsungPageSelection.select(viewport, listOf(
            ScreenBox(-900, 100, 900, 2100) to true,
            ScreenBox(900, 100, 2700, 2100) to true)))
    }
    @Test fun overlappingDuplicateCandidatesStayUnconfirmed() {
        assertNull(SamsungPageSelection.select(viewport, listOf(viewport to true, viewport to true)))
    }
    @Test fun hiddenOrVerticallyOffscreenNodesAreRejected() {
        assertNull(SamsungPageSelection.select(viewport, listOf(viewport to false)))
        assertNull(SamsungPageSelection.select(viewport, listOf(ScreenBox(0, 2200, 1800, 4000) to true)))
    }
    @Test fun narrowFoldViewportUsesItsOwnDimensions() {
        assertEquals(0, SamsungPageSelection.select(ScreenBox(0, 0, 900, 2200), listOf(
            ScreenBox(0, 100, 900, 2500) to true, ScreenBox(900, 100, 1800, 2500) to true)))
    }
}
