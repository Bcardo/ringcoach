package com.bcardo.ringcoach

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** A stopped/resumed read is discarded and repeated; network generation may continue after a valid read. */
internal class ForegroundReadGate {
    private data class State(val active: Boolean, val revision: Long)
    private val state = MutableStateFlow(State(false, 0))

    fun update(active: Boolean) {
        val previous = state.value
        if (previous.active != active) state.value = State(active, previous.revision + 1)
    }

    suspend fun <T> read(onWaiting: (Boolean) -> Unit = {}, block: suspend () -> T): T {
        try {
            while (true) {
                onWaiting(!state.value.active)
                val started = state.first { it.active }
                onWaiting(false)
                val result = try { block() } catch (exception: Exception) {
                    if (exception is kotlinx.coroutines.CancellationException || state.value == started) throw exception
                    continue
                }
                if (state.value == started) return result
            }
        } finally { onWaiting(false) }
    }
}
