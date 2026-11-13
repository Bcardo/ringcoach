package com.bcardo.ringcoach

internal data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(0)
    val height get() = (bottom - top).coerceAtLeast(0)
}

/** A pager can expose full-size offscreen siblings in the accessibility tree. Never guess on ties. */
internal object SamsungPageSelection {
    enum class Route { ENERGY, OPEN_ENERGY, BACK_TO_HOME, UNKNOWN }
    fun route(viewIds: Set<String>): Route = when {
        "vitality_main_view" in viewIds || "contributor_trend_layout" in viewIds -> Route.ENERGY
        "bia_root_view" in viewIds -> Route.BACK_TO_HOME
        "vitality_tile_score_text" in viewIds || "new_me_recycler_view" in viewIds -> Route.OPEN_ENERGY
        else -> Route.UNKNOWN
    }
    fun select(viewport: ScreenBox, pages: List<Pair<ScreenBox, Boolean>>): Int? {
        if (viewport.width == 0 || viewport.height == 0) return null
        val visible = pages.mapIndexedNotNull { index, (box, shown) ->
            val overlapWidth = minOf(box.right, viewport.right) - maxOf(box.left, viewport.left)
            val overlapHeight = minOf(box.bottom, viewport.bottom) - maxOf(box.top, viewport.top)
            index.takeIf { shown && box.width > viewport.width / 2 && overlapWidth > viewport.width / 2 && overlapHeight > 0 }
        }
        return visible.singleOrNull()
    }
}
