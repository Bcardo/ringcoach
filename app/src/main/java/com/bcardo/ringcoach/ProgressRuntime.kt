package com.bcardo.ringcoach

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

internal object ProgressRuntime {
    val revision = MutableStateFlow(0L)
    val running = MutableStateFlow(false)
    val mutex = Mutex()
    private val active = mutableSetOf<String>()
    private val requested = mutableSetOf<String>()
    @Synchronized fun changed() { revision.value++ }
    @Synchronized fun requestIfRunning(database: String): Boolean = (database in active).also { if (it) requested.add(database) }
    @Synchronized fun started(database: String) { active.add(database); running.value = active.isNotEmpty(); changed() }
    @Synchronized fun consumeRequested(database: String): Boolean = requested.remove(database)
    @Synchronized fun finished(database: String, completion: (Boolean) -> Unit) {
        completion(requested.remove(database))
        active.remove(database); running.value = active.isNotEmpty(); changed()
    }
}
